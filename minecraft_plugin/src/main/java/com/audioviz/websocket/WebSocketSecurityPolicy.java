package com.audioviz.websocket;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class WebSocketSecurityPolicy {
    private final String secret;

    public WebSocketSecurityPolicy(String secret) {
        this.secret = secret == null ? "" : secret.strip();
    }

    public boolean requiresAuthentication() {
        return !secret.isEmpty();
    }

    public boolean tokenMatches(String candidate) {
        if (!requiresAuthentication() || candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            secret.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    public static boolean isSafeConfiguration(String address, String ignoredSecret) {
        if (address == null || address.isBlank()) {
            return false;
        }

        String normalizedAddress = address.strip();
        if (normalizedAddress.equalsIgnoreCase("localhost")
            || normalizedAddress.equalsIgnoreCase("localhost.")) {
            return true;
        }

        if (normalizedAddress.matches("[0-9.]+")) {
            String[] octets = normalizedAddress.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            try {
                for (String octet : octets) {
                    int value = Integer.parseInt(octet);
                    if (value < 0 || value > 255) {
                        return false;
                    }
                }
                return Integer.parseInt(octets[0]) == 127;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        if (!normalizedAddress.contains(":")
            || !normalizedAddress.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(normalizedAddress).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
