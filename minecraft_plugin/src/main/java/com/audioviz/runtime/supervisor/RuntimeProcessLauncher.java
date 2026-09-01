package com.audioviz.runtime.supervisor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeProcessLauncher implements RuntimeSupervisor.ProcessLauncher {
    private static final int MAX_ARGUMENTS = 32;
    private static final int MAX_ARGUMENT_LENGTH = 32_768;
    private static final int MAX_LOG_LINE = 8_192;
    private static final int MAX_LOG_LINES_PER_SECOND = 100;
    private static final Duration LOG_READER_JOIN = Duration.ofSeconds(2);
    private static final Set<String> BASE_ENVIRONMENT_KEYS = Set.of(
        "PATH",
        "SYSTEMROOT",
        "WINDIR",
        "TEMP",
        "TMP",
        "LANG",
        "LC_ALL"
    );
    private static final Set<String> PROXY_ENVIRONMENT_KEYS = Set.of(
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "NO_PROXY"
    );
    private static final Map<String, String> THREAD_LIMITS = Map.of(
        "OMP_NUM_THREADS", "1",
        "OPENBLAS_NUM_THREADS", "1",
        "MKL_NUM_THREADS", "1",
        "NUMEXPR_NUM_THREADS", "1",
        "VECLIB_MAXIMUM_THREADS", "1"
    );

    private final ExecutorService logExecutor;
    private final ShutdownSender shutdownSender;
    private final LogSink logSink;
    private final Clock clock;
    private final CommandFactory commandFactory;
    private final Map<String, String> inheritedEnvironment;

    public RuntimeProcessLauncher(
        ExecutorService logExecutor,
        ShutdownSender shutdownSender,
        LogSink logSink
    ) {
        this(
            logExecutor,
            shutdownSender,
            logSink,
            Clock.systemUTC(),
            RuntimeProcessLauncher::productionCommand,
            System.getenv()
        );
    }

    RuntimeProcessLauncher(
        ExecutorService logExecutor,
        ShutdownSender shutdownSender,
        LogSink logSink,
        Clock clock,
        CommandFactory commandFactory,
        Map<String, String> inheritedEnvironment
    ) {
        this.logExecutor = Objects.requireNonNull(logExecutor, "logExecutor");
        this.shutdownSender = Objects.requireNonNull(shutdownSender, "shutdownSender");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
        this.inheritedEnvironment = Map.copyOf(
            Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment")
        );
    }

    @Override
    @SuppressFBWarnings(
        value = "COMMAND_INJECTION",
        justification = "The executable is an owned verified runtime file and every argument is " +
            "constructed or bounded as a separate ProcessBuilder token; no shell is involved."
    )
    public ManagedRuntimeProcess launch(RuntimeLaunch launch) throws LaunchException {
        Objects.requireNonNull(launch, "launch");
        validateOwnedPaths(launch);
        List<String> command = validateCommand(commandFactory.command(launch));
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(launch.workingDirectory().toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(baseEnvironment(inheritedEnvironment));
        environment.putAll(launch.environment());
        environment.put("MCAV_STATE_DIR", launch.stateDirectory().toString());
        environment.put("MCAV_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        environment.put("MCAV_PARENT_START_ID", currentProcessStartIdentity());
        environment.putAll(THREAD_LIMITS);
        environment.put("PYTHONDONTWRITEBYTECODE", "1");

        Process process;
        try {
            process = builder.start();
        } catch (IOException | SecurityException error) {
            throw new LaunchException(FailureReason.START_FAILED, error);
        }

        LineRedactor redactor = new LineRedactor(launch);
        Future<?> stdout;
        Future<?> stderr;
        try {
            stdout = logExecutor.submit(() -> drain(
                process.getInputStream(),
                OutputStream.STDOUT,
                redactor,
                new LogRateLimiter(clock)
            ));
            stderr = logExecutor.submit(() -> drain(
                process.getErrorStream(),
                OutputStream.STDERR,
                redactor,
                new LogRateLimiter(clock)
            ));
        } catch (RejectedExecutionException error) {
            process.destroyForcibly();
            throw new LaunchException(FailureReason.LOG_READER_UNAVAILABLE, error);
        }
        return new ManagedProcess(process, launch, stdout, stderr);
    }

    public static List<String> productionCommand(RuntimeLaunch launch) {
        Objects.requireNonNull(launch, "launch");
        return List.of(
            launch.entrypoint().toString(),
            "-m", "vj_server.cli",
            "--project-root", launch.workingDirectory().toString(),
            "--managed-by-paper",
            "--public-host", launch.publicHost(),
            "--public-port", Integer.toString(launch.publicPort())
        );
    }

    private static void validateOwnedPaths(RuntimeLaunch launch) throws LaunchException {
        if (
            !Files.isDirectory(launch.workingDirectory(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(launch.workingDirectory())
        ) {
            throw new LaunchException(FailureReason.WORKING_DIRECTORY_INVALID);
        }
        if (
            !Files.isDirectory(launch.stateDirectory(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(launch.stateDirectory())
        ) {
            throw new LaunchException(FailureReason.STATE_DIRECTORY_INVALID);
        }
        Path entrypoint = launch.entrypoint();
        if (
            !Files.isRegularFile(entrypoint, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(entrypoint)
        ) {
            throw new LaunchException(FailureReason.ENTRYPOINT_INVALID);
        }
        if (isWindows()) {
            Path fileName = entrypoint.getFileName();
            if (
                fileName == null ||
                !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
            ) {
                throw new LaunchException(FailureReason.ENTRYPOINT_INVALID);
            }
        } else if (!Files.isExecutable(entrypoint)) {
            throw new LaunchException(FailureReason.ENTRYPOINT_NOT_EXECUTABLE);
        }
    }

    static String currentProcessStartIdentity() throws LaunchException {
        if (isWindows()) {
            return ProcessHandle.current().info().startInstant()
                .map(value -> Long.toString(value.toEpochMilli()))
                .filter(RuntimeProcessLauncher::decimalIdentity)
                .orElseThrow(() -> new LaunchException(
                    FailureReason.PARENT_IDENTITY_UNAVAILABLE
                ));
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new LaunchException(FailureReason.PARENT_IDENTITY_UNAVAILABLE);
        }
        try {
            String stat = Files.readString(
                Path.of("/proc", Long.toString(ProcessHandle.current().pid()), "stat"),
                StandardCharsets.UTF_8
            );
            int closingParenthesis = stat.lastIndexOf(')');
            if (closingParenthesis < 0) {
                throw new IOException("invalid procfs process stat");
            }
            String[] fields = stat.substring(closingParenthesis + 1).trim().split("\\s+");
            if (fields.length <= 19 || !decimalIdentity(fields[19])) {
                throw new IOException("invalid procfs process start identity");
            }
            return fields[19];
        } catch (IOException | SecurityException error) {
            throw new LaunchException(FailureReason.PARENT_IDENTITY_UNAVAILABLE, error);
        }
    }

    private static boolean decimalIdentity(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<String> validateCommand(List<String> source) throws LaunchException {
        if (source == null || source.isEmpty() || source.size() > MAX_ARGUMENTS) {
            throw new LaunchException(FailureReason.COMMAND_INVALID);
        }
        List<String> command = new ArrayList<>(source.size());
        for (String argument : source) {
            if (
                argument == null || argument.isEmpty() ||
                argument.length() > MAX_ARGUMENT_LENGTH || argument.indexOf('\0') >= 0
            ) {
                throw new LaunchException(FailureReason.COMMAND_INVALID);
            }
            command.add(argument);
        }
        return List.copyOf(command);
    }

    private static Map<String, String> baseEnvironment(Map<String, String> source) {
        Map<String, String> normalized = new HashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(key.toUpperCase(Locale.ROOT), value);
            }
        });
        Map<String, String> result = new HashMap<>();
        for (String key : BASE_ENVIRONMENT_KEYS) {
            copyBounded(normalized, result, key);
        }
        for (String key : PROXY_ENVIRONMENT_KEYS) {
            String value = normalized.get(key);
            if (value != null && safeEnvironmentValue(value) && safeProxy(key, value)) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static void copyBounded(
        Map<String, String> source,
        Map<String, String> destination,
        String key
    ) {
        String value = source.get(key);
        if (value != null && safeEnvironmentValue(value)) {
            destination.put(key, value);
        }
    }

    private static boolean safeEnvironmentValue(String value) {
        return value.length() <= MAX_ARGUMENT_LENGTH &&
            value.indexOf('\0') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static boolean safeProxy(String key, String value) {
        if ("NO_PROXY".equals(key)) {
            return true;
        }
        try {
            URI proxy = new URI(value);
            return proxy.getUserInfo() == null &&
                proxy.getHost() != null &&
                ("http".equalsIgnoreCase(proxy.getScheme()) ||
                    "https".equalsIgnoreCase(proxy.getScheme()));
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private void drain(
        InputStream input,
        OutputStream stream,
        LineRedactor redactor,
        LogRateLimiter limiter
    ) {
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder(MAX_LOG_LINE);
            boolean truncated = false;
            char[] buffer = new char[4_096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    char character = buffer[index];
                    if (character == '\n') {
                        forward(stream, line, truncated, redactor, limiter);
                        line.setLength(0);
                        truncated = false;
                    } else if (character != '\r') {
                        if (line.length() < MAX_LOG_LINE) {
                            line.append(character);
                        } else {
                            truncated = true;
                        }
                    }
                }
            }
            if (!line.isEmpty() || truncated) {
                forward(stream, line, truncated, redactor, limiter);
            }
        } catch (IOException error) {
            safeLog(stream, "MCAV_RUNTIME_LOG_READ_FAILED");
        }
    }

    private void forward(
        OutputStream stream,
        StringBuilder line,
        boolean truncated,
        LineRedactor redactor,
        LogRateLimiter limiter
    ) {
        LogRateLimiter.Decision decision = limiter.accept();
        if (decision == LogRateLimiter.Decision.DROP) {
            return;
        }
        if (decision == LogRateLimiter.Decision.REPORT_LIMIT) {
            safeLog(stream, "MCAV_LOG_RATE_LIMITED");
            return;
        }
        String value = redactor.redact(line.toString());
        if (truncated) {
            value += " [MCAV_LOG_TRUNCATED]";
        }
        safeLog(stream, value);
    }

    private void safeLog(OutputStream stream, String line) {
        try {
            logSink.accept(stream, line);
        } catch (RuntimeException ignored) {
            // A logging sink cannot block process pipe draining.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public enum OutputStream {
        STDOUT,
        STDERR
    }

    @FunctionalInterface
    public interface ShutdownSender {
        void send(long generation);
    }

    @FunctionalInterface
    public interface LogSink {
        void accept(OutputStream stream, String line);
    }

    @FunctionalInterface
    interface CommandFactory {
        List<String> command(RuntimeLaunch launch);
    }

    public enum FailureReason {
        WORKING_DIRECTORY_INVALID,
        STATE_DIRECTORY_INVALID,
        ENTRYPOINT_INVALID,
        ENTRYPOINT_NOT_EXECUTABLE,
        COMMAND_INVALID,
        START_FAILED,
        LOG_READER_UNAVAILABLE,
        PARENT_IDENTITY_UNAVAILABLE
    }

    public static final class LaunchException extends Exception {
        private final FailureReason reason;

        LaunchException(FailureReason reason) {
            super(reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        LaunchException(FailureReason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public FailureReason reason() {
            return reason;
        }
    }

    private final class ManagedProcess implements ManagedRuntimeProcess {
        private final Process child;
        private final RuntimeLaunch launch;
        private final Future<?> stdout;
        private final Future<?> stderr;
        private final CompletionStage<Integer> exit;
        private final AtomicBoolean shutdownRequested = new AtomicBoolean();
        private final AtomicBoolean terminationStarted = new AtomicBoolean();

        ManagedProcess(
            Process child,
            RuntimeLaunch launch,
            Future<?> stdout,
            Future<?> stderr
        ) {
            this.child = child;
            this.launch = launch;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exit = child.onExit().thenApply(Process::exitValue);
        }

        @Override
        public RuntimeLaunch launch() {
            return launch;
        }

        @Override
        public long pid() {
            return child.pid();
        }

        @Override
        public CompletionStage<Integer> onExit() {
            return exit;
        }

        @Override
        public boolean isAlive() {
            return child.isAlive();
        }

        @Override
        public void requestShutdown() {
            if (shutdownRequested.compareAndSet(false, true)) {
                shutdownSender.send(launch.generation());
            }
        }

        @Override
        public void terminate(Duration graceful, Duration normal) {
            Duration gracefulWait = nonNegative(graceful, "graceful");
            Duration normalWait = nonNegative(normal, "normal");
            if (!terminationStarted.compareAndSet(false, true)) {
                return;
            }
            LinkedHashSet<ProcessHandle> owned = descendants(child);
            boolean exited = waitFor(child, gracefulWait);
            if (child.isAlive()) {
                owned.addAll(descendants(child));
                child.destroy();
            }
            destroy(owned, false);
            if (!exited) {
                waitFor(child, normalWait);
            }
            waitFor(owned, normalWait);
            destroy(owned, true);
            if (child.isAlive()) {
                child.destroyForcibly();
                waitFor(child, normalWait);
            }
            waitFor(owned, normalWait);
            awaitReader(stdout);
            awaitReader(stderr);
        }
    }

    private static LinkedHashSet<ProcessHandle> descendants(Process child) {
        LinkedHashSet<ProcessHandle> result = new LinkedHashSet<>();
        child.descendants().filter(ProcessHandle::isAlive).forEach(result::add);
        return result;
    }

    private static void destroy(Set<ProcessHandle> processes, boolean forcibly) {
        for (ProcessHandle process : processes) {
            if (!process.isAlive()) {
                continue;
            }
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }

    private static boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private static void waitFor(Set<ProcessHandle> processes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (processes.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadline) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void awaitReader(Future<?> reader) {
        try {
            reader.get(LOG_READER_JOIN.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException error) {
            reader.cancel(true);
        } catch (java.util.concurrent.ExecutionException ignored) {
            // The bounded reader reports its own reason-coded failure.
        }
    }

    private static Duration nonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    private static final class LineRedactor {
        private final List<String> secrets;
        private final List<String> paths;

        LineRedactor(RuntimeLaunch launch) {
            List<String> sensitiveValues = new ArrayList<>();
            sensitiveValues.add(launch.launchNonce());
            launch.environment().values().stream()
                .filter(value -> value.length() >= 4)
                .forEach(sensitiveValues::add);
            secrets = List.copyOf(sensitiveValues);
            paths = List.of(
                launch.entrypoint().toString(),
                launch.workingDirectory().toString(),
                launch.stateDirectory().toString()
            );
        }

        String redact(String source) {
            String redacted = source;
            for (String secret : secrets) {
                redacted = redacted.replace(secret, "[REDACTED]");
            }
            for (String path : paths) {
                redacted = redacted.replace(path, "[RUNTIME_PATH]");
            }
            return redacted;
        }
    }

    private static final class LogRateLimiter {
        private final Clock clock;
        private Instant window;
        private int accepted;
        private boolean reported;

        LogRateLimiter(Clock clock) {
            this.clock = clock;
            this.window = clock.instant();
        }

        Decision accept() {
            Instant now = clock.instant();
            if (!now.isBefore(window.plusSeconds(1))) {
                window = now;
                accepted = 0;
                reported = false;
            }
            if (accepted < MAX_LOG_LINES_PER_SECOND) {
                accepted++;
                return Decision.ACCEPT;
            }
            if (!reported) {
                reported = true;
                return Decision.REPORT_LIMIT;
            }
            return Decision.DROP;
        }

        enum Decision {
            ACCEPT,
            REPORT_LIMIT,
            DROP
        }
    }
}
