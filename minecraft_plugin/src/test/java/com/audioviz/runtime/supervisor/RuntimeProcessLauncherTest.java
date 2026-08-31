package com.audioviz.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.audioviz.runtime.release.RuntimePlatform;
import com.audioviz.runtime.store.RuntimeVersionId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeProcessLauncherTest {
    private static final String SECRET = "renderer-secret-that-must-never-leak";
    private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @TempDir
    Path temporaryDirectory;

    private ExecutorService readers;
    private List<ManagedRuntimeProcess> launched;

    @BeforeEach
    void setUp() {
        readers = Executors.newFixedThreadPool(2);
        launched = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (ManagedRuntimeProcess process : launched) {
            if (process.isAlive()) {
                process.terminate(Duration.ZERO, Duration.ofMillis(200));
            }
        }
        readers.shutdownNow();
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS));
        for (ManagedRuntimeProcess process : launched) {
            assertFalse(process.isAlive(), "fixture process leaked");
        }
    }

    @Test
    void productionCommandUsesSeparateFixedArgumentsAndContainsNoSecret() throws Exception {
        RuntimeLaunch launch = launchFixture(false);

        List<String> command = RuntimeProcessLauncher.productionCommand(launch);

        assertEquals(launch.entrypoint().toString(), command.get(0));
        assertEquals(
            List.of(
                "--managed-by-paper",
                "--state-dir", launch.stateDirectory().toString(),
                "--public-host", "0.0.0.0",
                "--public-port", "9000"
            ),
            command.subList(1, command.size())
        );
        assertFalse(command.stream().anyMatch(argument -> argument.contains(SECRET)));
        assertTrue(command.contains(launch.stateDirectory().toString()));
    }

    @Test
    void secretsAreEnvironmentOnlyAndInheritedEnvironmentIsAllowlisted() throws Exception {
        FixtureFiles fixture = fixtureFiles();
        List<String> logs = new CopyOnWriteArrayList<>();
        RuntimeProcessLauncher launcher = launcher(fixture, 0, false, false, logs);

        ManagedRuntimeProcess process = track(launcher.launch(launchFixture(false)));
        Properties report = awaitReport(fixture.report());
        process.requestShutdown();
        process.terminate(Duration.ofSeconds(2), Duration.ofMillis(200));

        assertEquals(SECRET, report.getProperty("environment.MCAV_RENDERER_SECRET"));
        assertEquals("1", report.getProperty("environment.OPENBLAS_NUM_THREADS"));
        assertEquals("1", report.getProperty("environment.OMP_NUM_THREADS"));
        assertEquals("1", report.getProperty("environment.MKL_NUM_THREADS"));
        assertEquals("1", report.getProperty("environment.NUMEXPR_NUM_THREADS"));
        assertEquals("1", report.getProperty("environment.VECLIB_MAXIMUM_THREADS"));
        assertFalse(report.containsKey("environment.AWS_SECRET_ACCESS_KEY"));
        assertEquals(
            "http://proxy.example.test:8080",
            report.getProperty("environment.HTTP_PROXY")
        );
        assertFalse(report.containsKey("environment.HTTPS_PROXY"));
        assertFalse(logs.stream().anyMatch(line -> line.contains(SECRET)));
        assertTrue(logs.stream().anyMatch(line -> line.contains("[REDACTED]")));
    }

    @Test
    void launchEnvironmentRejectsNonMcavKeys() throws Exception {
        RuntimeLaunch valid = launchFixture(false);

        assertThrows(
            IllegalArgumentException.class,
            () -> new RuntimeLaunch(
                valid.version(),
                valid.entrypoint(),
                valid.workingDirectory(),
                valid.stateDirectory(),
                valid.publicHost(),
                valid.publicPort(),
                Map.of("AWS_SECRET_ACCESS_KEY", "must-not-cross-boundary"),
                valid.launchNonce(),
                valid.generation(),
                valid.runtimeApi()
            )
        );
    }

    @Test
    void drainsMegabyteBurstsWithoutBlockingAndBoundsForwardedLines() throws Exception {
        FixtureFiles fixture = fixtureFiles();
        List<String> logs = new CopyOnWriteArrayList<>();
        RuntimeProcessLauncher launcher = launcher(fixture, 1_200_000, false, false, logs);

        ManagedRuntimeProcess process = track(launcher.launch(launchFixture(false)));
        awaitReport(fixture.report());
        process.requestShutdown();
        process.terminate(Duration.ofSeconds(3), Duration.ofMillis(200));
        int exitCode = process.onExit().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(0, exitCode);
        assertTrue(logs.stream().anyMatch(line -> line.contains("MCAV_LOG_TRUNCATED")));
        assertTrue(logs.stream().allMatch(line -> line.length() <= 8_256));
    }

    @Test
    void gracefulShutdownUsesInjectedControlCallback() throws Exception {
        FixtureFiles fixture = fixtureFiles();
        AtomicInteger shutdowns = new AtomicInteger();
        RuntimeProcessLauncher launcher = launcher(
            fixture,
            0,
            false,
            false,
            new CopyOnWriteArrayList<>(),
            generation -> {
                shutdowns.incrementAndGet();
                try {
                    Files.createFile(fixture.shutdownMarker());
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
        );

        ManagedRuntimeProcess process = track(launcher.launch(launchFixture(false)));
        awaitReport(fixture.report());
        process.requestShutdown();
        process.terminate(Duration.ofSeconds(2), Duration.ofMillis(200));

        assertEquals(1, shutdowns.get());
        assertEquals(0, process.onExit().toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void forcedTerminationStopsAnUnresponsiveChild() throws Exception {
        FixtureFiles fixture = fixtureFiles();
        RuntimeProcessLauncher launcher = launcher(
            fixture, 0, false, true, new CopyOnWriteArrayList<>()
        );

        ManagedRuntimeProcess process = track(launcher.launch(launchFixture(false)));
        awaitReport(fixture.report());
        process.requestShutdown();
        process.terminate(Duration.ofMillis(50), Duration.ofMillis(50));

        assertFalse(process.isAlive());
        assertTrue(process.onExit().toCompletableFuture().get(5, TimeUnit.SECONDS) != 0);
    }

    @Test
    void terminationContainsKnownDescendantProcess() throws Exception {
        FixtureFiles fixture = fixtureFiles();
        RuntimeProcessLauncher launcher = launcher(
            fixture, 0, true, true, new CopyOnWriteArrayList<>()
        );

        ManagedRuntimeProcess process = track(launcher.launch(launchFixture(false)));
        Properties report = awaitReport(fixture.report());
        long descendantPid = Long.parseLong(report.getProperty("descendant.pid"));
        ProcessHandle descendant = ProcessHandle.of(descendantPid).orElseThrow();
        assertTrue(descendant.isAlive());

        process.terminate(Duration.ofMillis(50), Duration.ofMillis(100));

        awaitNotAlive(descendant);
        assertFalse(descendant.isAlive());
    }

    @Test
    void missingWorkingDirectoryAndEntrypointFailBeforeProcessStart() throws Exception {
        FixtureFiles fixture = fixtureFiles();
        RuntimeProcessLauncher launcher = launcher(fixture, 0, false, false, List.of());
        RuntimeLaunch valid = launchFixture(false);
        RuntimeLaunch missingDirectory = new RuntimeLaunch(
            valid.version(),
            temporaryDirectory.resolve("missing/fixture.exe"),
            temporaryDirectory.resolve("missing"),
            valid.stateDirectory(),
            valid.publicHost(),
            valid.publicPort(),
            valid.environment(),
            valid.launchNonce(),
            valid.generation(),
            valid.runtimeApi()
        );
        Files.delete(valid.entrypoint());

        assertThrows(RuntimeProcessLauncher.LaunchException.class, () -> launcher.launch(valid));
        assertThrows(
            RuntimeProcessLauncher.LaunchException.class,
            () -> launcher.launch(missingDirectory)
        );
    }

    @Test
    void posixEntrypointMustBeExecutable() throws Exception {
        assumeFalse(isWindows());
        FixtureFiles fixture = fixtureFiles();
        RuntimeProcessLauncher launcher = launcher(fixture, 0, false, false, List.of());
        RuntimeLaunch launch = launchFixture(false);
        Files.setPosixFilePermissions(
            launch.entrypoint(),
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        );

        assertThrows(RuntimeProcessLauncher.LaunchException.class, () -> launcher.launch(launch));
    }

    private RuntimeProcessLauncher launcher(
        FixtureFiles fixture,
        int burstBytes,
        boolean spawnDescendant,
        boolean ignoreShutdown,
        List<String> logs
    ) {
        return launcher(
            fixture,
            burstBytes,
            spawnDescendant,
            ignoreShutdown,
            logs,
            generation -> {
                try {
                    Files.createFile(fixture.shutdownMarker());
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Repeated control requests remain harmless in the fixture.
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
        );
    }

    private RuntimeProcessLauncher launcher(
        FixtureFiles fixture,
        int burstBytes,
        boolean spawnDescendant,
        boolean ignoreShutdown,
        List<String> logs,
        RuntimeProcessLauncher.ShutdownSender shutdownSender
    ) {
        Map<String, String> inherited = new HashMap<>(System.getenv());
        inherited.put("AWS_SECRET_ACCESS_KEY", "must-not-cross-process-boundary");
        inherited.put("HTTP_PROXY", "http://proxy.example.test:8080");
        inherited.put("HTTPS_PROXY", "http://user:password@proxy.example.test:8080");
        return new RuntimeProcessLauncher(
            readers,
            shutdownSender,
            (stream, line) -> logs.add(stream + ":" + line),
            Clock.systemUTC(),
            launch -> fixtureCommand(
                fixture, burstBytes, spawnDescendant, ignoreShutdown
            ),
            inherited
        );
    }

    private List<String> fixtureCommand(
        FixtureFiles fixture,
        int burstBytes,
        boolean spawnDescendant,
        boolean ignoreShutdown
    ) {
        List<String> command = new ArrayList<>(List.of(
            javaExecutable().toString(),
            "-cp",
            System.getProperty("java.class.path"),
            FixtureChildProcess.class.getName(),
            "--fixture-report", fixture.report().toString(),
            "--fixture-shutdown-marker", fixture.shutdownMarker().toString(),
            "--fixture-burst", Integer.toString(burstBytes)
        ));
        if (spawnDescendant) {
            command.add("--fixture-spawn-descendant");
        }
        if (ignoreShutdown) {
            command.add("--fixture-ignore-shutdown");
        }
        return command;
    }

    private RuntimeLaunch launchFixture(boolean missingDirectory) throws Exception {
        Path root = temporaryDirectory.resolve(missingDirectory ? "missing" : "runtime with spaces");
        if (!missingDirectory) {
            Files.createDirectories(root);
        }
        Path entrypoint = root.resolve(isWindows() ? "fixture.exe" : "fixture");
        if (!missingDirectory) {
            Files.createFile(entrypoint);
            if (!isWindows()) {
                Files.setPosixFilePermissions(
                    entrypoint,
                    EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                );
            }
        }
        return new RuntimeLaunch(
            RuntimeVersionId.of(
                "1.2.0",
                isWindows() ? RuntimePlatform.WINDOWS_X86_64 : RuntimePlatform.LINUX_X86_64
            ),
            entrypoint,
            root,
            root.resolve("state with spaces"),
            "0.0.0.0",
            9000,
            Map.of("MCAV_RENDERER_SECRET", SECRET),
            NONCE,
            42,
            1
        );
    }

    private FixtureFiles fixtureFiles() {
        return new FixtureFiles(
            temporaryDirectory.resolve("fixture-report.properties"),
            temporaryDirectory.resolve("fixture-shutdown.marker")
        );
    }

    private ManagedRuntimeProcess track(ManagedRuntimeProcess process) {
        launched.add(process);
        return process;
    }

    private static Properties awaitReport(Path report) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(report) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(report), "fixture did not publish its report");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(report)) {
            properties.load(input);
        }
        return properties;
    }

    private static void awaitNotAlive(ProcessHandle handle) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (handle.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static Path javaExecutable() {
        return Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win");
    }

    private record FixtureFiles(Path report, Path shutdownMarker) { }
}
