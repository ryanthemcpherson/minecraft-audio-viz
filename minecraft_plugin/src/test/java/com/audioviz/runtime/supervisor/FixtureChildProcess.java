package com.audioviz.runtime.supervisor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class FixtureChildProcess {
    private FixtureChildProcess() { }

    public static void main(String[] arguments) throws Exception {
        List<String> args = List.of(arguments);
        if (args.contains("--descendant")) {
            waitForever();
            return;
        }

        Path report = requiredPath(args, "--fixture-report");
        Path shutdownMarker = requiredPath(args, "--fixture-shutdown-marker");
        int burstBytes = integer(args, "--fixture-burst", 0);
        boolean spawnDescendant = args.contains("--fixture-spawn-descendant");
        boolean ignoreShutdown = args.contains("--fixture-ignore-shutdown");

        Process descendant = spawnDescendant ? spawnDescendant() : null;
        writeReport(report, args, descendant);
        emitBurst(System.out, burstBytes, 'o');
        emitBurst(System.err, burstBytes, 'e');
        String secret = System.getenv("MCAV_RENDERER_SECRET");
        if (secret != null) {
            System.out.println("fixture-secret=" + secret);
        }
        System.out.flush();
        System.err.flush();

        if (ignoreShutdown) {
            waitForever();
            return;
        }
        InstantDeadline deadline = new InstantDeadline(Duration.ofSeconds(20));
        while (!Files.exists(shutdownMarker) && !deadline.expired()) {
            Thread.sleep(10);
        }
    }

    private static Process spawnDescendant() throws IOException {
        return new ProcessBuilder(
            javaExecutable().toString(),
            "-cp",
            System.getProperty("java.class.path"),
            FixtureChildProcess.class.getName(),
            "--descendant"
        ).start();
    }

    private static void writeReport(Path report, List<String> args, Process descendant)
        throws IOException {
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("argument.count", Integer.toString(args.size()));
        for (int index = 0; index < args.size(); index++) {
            properties.setProperty("argument." + index, args.get(index));
        }
        for (String key : List.of(
            "MCAV_RENDERER_SECRET",
            "AWS_SECRET_ACCESS_KEY",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "OPENBLAS_NUM_THREADS",
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "NUMEXPR_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS"
        )) {
            String value = System.getenv(key);
            if (value != null) {
                properties.setProperty("environment." + key, value);
            }
        }
        if (descendant != null) {
            properties.setProperty("descendant.pid", Long.toString(descendant.pid()));
        }
        Files.createDirectories(report.getParent());
        Path temporaryReport = report.resolveSibling(report.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(
            temporaryReport,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            properties.store(output, "fixture report");
        }
        try {
            Files.move(
                temporaryReport,
                report,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporaryReport, report);
        }
    }

    private static void emitBurst(java.io.PrintStream output, int bytes, char value) {
        byte[] chunk = String.valueOf(value).repeat(4_096).getBytes(StandardCharsets.UTF_8);
        int remaining = bytes;
        while (remaining > 0) {
            int length = Math.min(remaining, chunk.length);
            output.write(chunk, 0, length);
            remaining -= length;
        }
        output.println();
    }

    private static int integer(List<String> args, String name, int defaultValue) {
        int index = args.indexOf(name);
        return index < 0 ? defaultValue : Integer.parseInt(args.get(index + 1));
    }

    private static Path requiredPath(List<String> args, String name) {
        int index = args.indexOf(name);
        if (index < 0 || index + 1 >= args.size()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return Path.of(args.get(index + 1));
    }

    private static Path javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win");
    }

    private static void waitForever() throws InterruptedException {
        while (true) {
            Thread.sleep(1_000);
        }
    }

    private static final class InstantDeadline {
        private final long deadlineNanos;

        InstantDeadline(Duration duration) {
            deadlineNanos = System.nanoTime() + duration.toNanos();
        }

        boolean expired() {
            return System.nanoTime() >= deadlineNanos;
        }
    }
}
