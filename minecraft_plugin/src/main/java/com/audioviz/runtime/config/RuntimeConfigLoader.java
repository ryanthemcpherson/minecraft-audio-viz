package com.audioviz.runtime.config;

import com.audioviz.runtime.config.RuntimeConfig.PerformanceConfig;
import com.audioviz.runtime.config.RuntimeConfig.PerformanceProfile;
import com.audioviz.runtime.config.RuntimeConfig.ReleaseChannel;
import com.audioviz.runtime.config.RuntimeConfig.ResourceProfile;
import com.audioviz.runtime.config.RuntimeConfig.TlsConfig;
import com.audioviz.runtime.config.RuntimeConfig.TlsMode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;

public final class RuntimeConfigLoader {
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
        .withZone(ZoneOffset.UTC);
    private static final String RUNTIME_BLOCK = String.join("\n",
        "runtime:",
        "  enabled: true",
        "  channel: stable",
        "  install-on-start: true",
        "  public-port: 8080",
        "  public-host: \"0.0.0.0\"",
        "  public-url: \"\"",
        "  readiness-timeout-seconds: 30",
        "  resource-profile: auto",
        "  retain-versions: 3",
        "  tls:",
        "    mode: generated",
        "    certificate: \"\"",
        "    private-key: \"\""
    );
    private static final List<String> RUNTIME_SCALARS = List.of(
        "enabled: true",
        "channel: stable",
        "install-on-start: true",
        "public-port: 8080",
        "public-host: \"0.0.0.0\"",
        "public-url: \"\"",
        "readiness-timeout-seconds: 30",
        "resource-profile: auto",
        "retain-versions: 3"
    );
    private static final List<String> TLS_SCALARS = List.of(
        "mode: generated",
        "certificate: \"\"",
        "private-key: \"\""
    );
    private static final List<String> PERFORMANCE_SCALARS = List.of(
        "profile: balanced",
        "entity-budget: 160",
        "target-render-fps: 20",
        "tps-load-shedding: true"
    );

    private final Clock clock;

    public RuntimeConfigLoader() {
        this(Clock.systemUTC());
    }

    RuntimeConfigLoader(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RuntimeConfig load(FileConfiguration config, Path dataDirectory)
        throws ConfigException {
        Objects.requireNonNull(config, "config");
        Path ownedRoot = Objects.requireNonNull(dataDirectory, "dataDirectory")
            .toAbsolutePath()
            .normalize();
        boolean enabled = bool(config, "runtime.enabled", true);
        ReleaseChannel channel = enumValue(
            string(config, "runtime.channel", "stable"),
            ReleaseChannel.class
        );
        boolean installOnStart = bool(config, "runtime.install-on-start", true);
        int publicPort = integer(config, "runtime.public-port", 8080, 1, 65_535);
        String publicHost = publicHost(string(config, "runtime.public-host", "0.0.0.0"));
        Optional<URI> publicUrl = publicUrl(string(config, "runtime.public-url", ""));
        int readinessSeconds = integer(
            config,
            "runtime.readiness-timeout-seconds",
            30,
            5,
            300
        );
        ResourceProfile resourceProfile = enumValue(
            string(config, "runtime.resource-profile", "auto"),
            ResourceProfile.class
        );
        int retainVersions = integer(config, "runtime.retain-versions", 3, 3, 10);
        TlsMode tlsMode = enumValue(
            string(config, "runtime.tls.mode", "generated"),
            TlsMode.class
        );
        Optional<Path> certificate = ownedPath(
            string(config, "runtime.tls.certificate", ""),
            ownedRoot
        );
        Optional<Path> privateKey = ownedPath(
            string(config, "runtime.tls.private-key", ""),
            ownedRoot
        );
        validateTls(tlsMode, certificate, privateKey);

        PerformanceProfile performanceProfile = enumValue(
            string(config, "performance.profile", "balanced"),
            PerformanceProfile.class
        );
        int entityBudget = integer(config, "performance.entity-budget", 160, 16, 2_000);
        int targetFps = integer(config, "performance.target-render-fps", 20, 1, 60);
        boolean loadShedding = bool(config, "performance.tps-load-shedding", true);
        String rendererAddress = rendererAddress(
            string(config, "websocket.address", "127.0.0.1")
        );
        return new RuntimeConfig(
            enabled,
            channel,
            installOnStart,
            publicHost,
            publicPort,
            publicUrl,
            Duration.ofSeconds(readinessSeconds),
            resourceProfile,
            retainVersions,
            new TlsConfig(tlsMode, certificate, privateKey),
            new PerformanceConfig(
                performanceProfile,
                entityBudget,
                targetFps,
                loadShedding
            ),
            rendererAddress
        );
    }

    public MigrationResult migrate(Path configFile) throws ConfigException {
        Path target = Objects.requireNonNull(configFile, "configFile")
            .toAbsolutePath()
            .normalize();
        if (
            Files.isSymbolicLink(target) ||
            !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(FailureReason.CONFIG_PATH_INVALID);
        }
        byte[] before;
        try {
            long size = Files.size(target);
            if (size < 0 || size > MAX_CONFIG_BYTES) {
                throw failure(FailureReason.CONFIG_TOO_LARGE);
            }
            before = Files.readAllBytes(target);
        } catch (IOException error) {
            throw failure(FailureReason.IO_FAILURE, error);
        }
        String source = decode(before);
        String migrated = patch(source);
        byte[] after = migrated.getBytes(StandardCharsets.UTF_8);
        if (java.util.Arrays.equals(before, after)) {
            return new MigrationResult(false, Optional.empty());
        }

        Path backup = target.resolveSibling(
            target.getFileName() + ".backup-" + BACKUP_TIME.format(clock.instant())
        );
        Path temporary = target.resolveSibling(
            "." + target.getFileName() + ".runtime-migration-" + UUID.randomUUID() + ".tmp"
        );
        try {
            writeForced(backup, before);
            writeForced(temporary, after);
            replace(temporary, target);
            return new MigrationResult(true, Optional.of(backup));
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw failure(FailureReason.IO_FAILURE, error);
        }
    }

    private static String patch(String source) throws ConfigException {
        String newline = newline(source);
        List<String> lines = splitLines(source);
        rejectAmbiguous(lines);

        Section runtime = section(lines, "runtime");
        if (runtime == null) {
            appendBlock(lines, RUNTIME_BLOCK, newline);
        } else {
            patchRuntime(lines, newline);
        }
        Section performance = section(lines, "performance");
        if (performance == null) {
            appendBlock(
                lines,
                "performance:\n  " + String.join("\n  ", PERFORMANCE_SCALARS),
                newline
            );
        } else {
            appendMissing(lines, performance, 2, PERFORMANCE_SCALARS, newline);
        }
        return String.join("", lines);
    }

    private static void patchRuntime(List<String> lines, String newline)
        throws ConfigException {
        Section runtime = section(lines, "runtime");
        appendMissing(lines, runtime, 2, RUNTIME_SCALARS, newline);
        runtime = section(lines, "runtime");
        Section tls = nestedSection(lines, runtime, "tls", 2);
        if (tls == null) {
            List<String> additions = new ArrayList<>();
            additions.add("  tls:" + newline);
            TLS_SCALARS.forEach(value -> additions.add("    " + value + newline));
            lines.addAll(runtime.end(), additions);
        } else {
            appendMissing(lines, tls, 4, TLS_SCALARS, newline);
        }
    }

    private static void appendMissing(
        List<String> lines,
        Section section,
        int indentation,
        List<String> defaults,
        String newline
    ) throws ConfigException {
        Set<String> present = new HashSet<>();
        for (int index = section.start() + 1; index < section.end(); index++) {
            String content = withoutNewline(lines.get(index));
            if (leadingSpaces(content) != indentation || content.stripLeading().startsWith("#")) {
                continue;
            }
            String trimmed = content.stripLeading();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon);
            if (!present.add(key)) {
                throw failure(FailureReason.AMBIGUOUS_CONFIG);
            }
        }
        List<String> additions = new ArrayList<>();
        for (String value : defaults) {
            String key = value.substring(0, value.indexOf(':'));
            if (!present.contains(key)) {
                additions.add(" ".repeat(indentation) + value + newline);
            }
        }
        lines.addAll(section.end(), additions);
    }

    private static void rejectAmbiguous(List<String> lines) throws ConfigException {
        int runtimeSections = 0;
        int performanceSections = 0;
        for (String raw : lines) {
            String line = withoutNewline(raw);
            int indentation = leadingSpaces(line);
            if (hasIndentationTab(line)) {
                throw failure(FailureReason.AMBIGUOUS_CONFIG);
            }
            if (indentation != 0) {
                continue;
            }
            String trimmed = line.strip();
            if (isSectionLine(trimmed, "runtime")) {
                runtimeSections++;
            } else if (trimmed.startsWith("runtime:")) {
                throw failure(FailureReason.AMBIGUOUS_CONFIG);
            }
            if (isSectionLine(trimmed, "performance")) {
                performanceSections++;
            } else if (trimmed.startsWith("performance:")) {
                throw failure(FailureReason.AMBIGUOUS_CONFIG);
            }
        }
        if (runtimeSections > 1 || performanceSections > 1) {
            throw failure(FailureReason.AMBIGUOUS_CONFIG);
        }
    }

    private static Section section(List<String> lines, String name) {
        for (int index = 0; index < lines.size(); index++) {
            String line = withoutNewline(lines.get(index));
            if (leadingSpaces(line) == 0 && isSectionLine(line.strip(), name)) {
                return new Section(index, sectionEnd(lines, index, 0));
            }
        }
        return null;
    }

    private static Section nestedSection(
        List<String> lines,
        Section parent,
        String name,
        int indentation
    ) throws ConfigException {
        Section result = null;
        for (int index = parent.start() + 1; index < parent.end(); index++) {
            String line = withoutNewline(lines.get(index));
            if (
                leadingSpaces(line) == indentation &&
                isSectionLine(line.strip(), name)
            ) {
                if (result != null) {
                    throw failure(FailureReason.AMBIGUOUS_CONFIG);
                }
                result = new Section(index, sectionEnd(lines, index, indentation));
            }
        }
        return result;
    }

    private static int sectionEnd(List<String> lines, int start, int indentation) {
        for (int index = start + 1; index < lines.size(); index++) {
            String line = withoutNewline(lines.get(index));
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (leadingSpaces(line) <= indentation) {
                return index;
            }
        }
        return lines.size();
    }

    private static boolean isSectionLine(String line, String name) {
        return line.equals(name + ":") || line.startsWith(name + ": #");
    }

    private static void appendBlock(List<String> lines, String block, String newline) {
        if (!lines.isEmpty()) {
            String last = lines.getLast();
            if (!last.endsWith("\n") && !last.endsWith("\r")) {
                lines.set(lines.size() - 1, last + newline);
            }
            if (!withoutNewline(lines.getLast()).isBlank()) {
                lines.add(newline);
            }
        }
        for (String line : block.split("\n", -1)) {
            lines.add(line + newline);
        }
    }

    private static List<String> splitLines(String source) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                lines.add(source.substring(start, index + 1));
                start = index + 1;
            }
        }
        if (start < source.length()) {
            lines.add(source.substring(start));
        }
        return lines;
    }

    private static String newline(String source) throws ConfigException {
        boolean crlf = source.contains("\r\n");
        String withoutCrlf = source.replace("\r\n", "");
        if (withoutCrlf.indexOf('\r') >= 0 || (crlf && withoutCrlf.indexOf('\n') >= 0)) {
            throw failure(FailureReason.AMBIGUOUS_CONFIG);
        }
        return crlf ? "\r\n" : "\n";
    }

    private static String withoutNewline(String line) {
        if (line.endsWith("\r\n")) {
            return line.substring(0, line.length() - 2);
        }
        if (line.endsWith("\n") || line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private static boolean hasIndentationTab(String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\t') {
                return true;
            }
            if (character != ' ') {
                return false;
            }
        }
        return false;
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String decode(byte[] bytes) throws ConfigException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw failure(FailureReason.CONFIG_ENCODING_INVALID, error);
        }
    }

    private static boolean bool(FileConfiguration config, String path, boolean defaultValue)
        throws ConfigException {
        if (!config.contains(path)) {
            return defaultValue;
        }
        if (!config.isBoolean(path)) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        return config.getBoolean(path);
    }

    private static int integer(
        FileConfiguration config,
        String path,
        int defaultValue,
        int minimum,
        int maximum
    ) throws ConfigException {
        if (!config.contains(path)) {
            return defaultValue;
        }
        if (!config.isInt(path)) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        int value = config.getInt(path);
        if (value < minimum || value > maximum) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        return value;
    }

    private static String string(FileConfiguration config, String path, String defaultValue)
        throws ConfigException {
        if (!config.contains(path)) {
            return defaultValue;
        }
        if (!config.isString(path)) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        String value = config.getString(path);
        if (value == null || value.length() > 2_048) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        return value.strip();
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type)
        throws ConfigException {
        try {
            return Enum.valueOf(type, value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw failure(FailureReason.INVALID_VALUE, error);
        }
    }

    private static String publicHost(String value) throws ConfigException {
        if (value.equals("0.0.0.0") || value.equals("::")) {
            return value;
        }
        if (value.equalsIgnoreCase("localhost")) {
            return "localhost";
        }
        if (!value.matches("[0-9A-Fa-f:.]+")) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!address.isLoopbackAddress() && NetworkInterface.getByInetAddress(address) == null) {
                throw failure(FailureReason.INVALID_VALUE);
            }
            return address.getHostAddress();
        } catch (IOException error) {
            throw failure(FailureReason.INVALID_VALUE, error);
        }
    }

    private static Optional<URI> publicUrl(String value) throws ConfigException {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value);
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                uri.getUserInfo() != null ||
                uri.getQuery() != null ||
                uri.getFragment() != null ||
                !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
            ) {
                throw failure(FailureReason.INVALID_VALUE);
            }
            return Optional.of(uri);
        } catch (URISyntaxException error) {
            throw failure(FailureReason.INVALID_VALUE, error);
        }
    }

    private static Optional<Path> ownedPath(String value, Path root) throws ConfigException {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Path supplied;
        try {
            supplied = Path.of(value);
        } catch (RuntimeException error) {
            throw failure(FailureReason.INVALID_VALUE, error);
        }
        Path resolved = supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
        if (!resolved.startsWith(root)) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        return Optional.of(resolved);
    }

    private static void validateTls(
        TlsMode mode,
        Optional<Path> certificate,
        Optional<Path> privateKey
    ) throws ConfigException {
        if (mode == TlsMode.GENERATED && (certificate.isPresent() || privateKey.isPresent())) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        if (mode == TlsMode.PROVIDED && (certificate.isEmpty() || privateKey.isEmpty())) {
            throw failure(FailureReason.INVALID_VALUE);
        }
        if (certificate.isPresent() && certificate.equals(privateKey)) {
            throw failure(FailureReason.INVALID_VALUE);
        }
    }

    private static String rendererAddress(String value) throws ConfigException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "127.0.0.1" -> "127.0.0.1";
            case "localhost" -> "localhost";
            case "::1" -> "::1";
            default -> throw failure(FailureReason.RENDERER_NOT_LOOPBACK);
        };
    }

    public enum FailureReason {
        INVALID_VALUE,
        RENDERER_NOT_LOOPBACK,
        CONFIG_PATH_INVALID,
        CONFIG_TOO_LARGE,
        CONFIG_ENCODING_INVALID,
        AMBIGUOUS_CONFIG,
        IO_FAILURE
    }

    public static final class ConfigException extends Exception {
        private final FailureReason reason;

        ConfigException(FailureReason reason) {
            super(reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        ConfigException(FailureReason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public FailureReason reason() {
            return reason;
        }
    }

    public record MigrationResult(boolean changed, Optional<Path> backup) {
        public MigrationResult {
            backup = Objects.requireNonNull(backup, "backup");
            if (changed != backup.isPresent()) {
                throw new IllegalArgumentException("migration backup must match changed state");
            }
        }
    }

    private record Section(int start, int end) { }

    private static ConfigException failure(FailureReason reason) {
        return new ConfigException(reason);
    }

    private static ConfigException failure(FailureReason reason, Throwable cause) {
        return new ConfigException(reason, cause);
    }
}
