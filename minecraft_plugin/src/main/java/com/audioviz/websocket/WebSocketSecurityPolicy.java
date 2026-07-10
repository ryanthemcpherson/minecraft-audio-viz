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

    public static boolean isSafeConfiguration(String address, String secret) {
        String normalizedSecret = secret == null ? "" : secret.strip();
        if (address == null || address.isBlank()) {
            return false;
        }
        if (!normalizedSecret.isEmpty()) {
            return true;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
