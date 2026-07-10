package com.audioviz.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketSecurityPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "::1"})
    void allowsLoopbackBindingWithoutSecret(String address) {
        assertTrue(WebSocketSecurityPolicy.isSafeConfiguration(address, ""));
        assertTrue(WebSocketSecurityPolicy.isSafeConfiguration(address, "   "));
        assertTrue(WebSocketSecurityPolicy.isSafeConfiguration(address, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "192.168.1.25", "10.0.0.8", "not-a-host.invalid"})
    void rejectsNonLoopbackBindingWithoutSecret(String address) {
        assertFalse(WebSocketSecurityPolicy.isSafeConfiguration(address, ""));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingBindingAddress(String address) {
        assertFalse(WebSocketSecurityPolicy.isSafeConfiguration(address, "secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "192.168.1.25", "audio-viz.internal"})
    void allowsNonLoopbackBindingWithSecret(String address) {
        assertTrue(WebSocketSecurityPolicy.isSafeConfiguration(address, "secret"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void emptySecretDoesNotRequireAuthentication(String secret) {
        WebSocketSecurityPolicy policy = new WebSocketSecurityPolicy(secret);

        assertFalse(policy.requiresAuthentication());
        assertFalse(policy.tokenMatches("anything"));
    }

    @Test
    void acceptsOnlyExactToken() {
        WebSocketSecurityPolicy policy = new WebSocketSecurityPolicy("  exact-token  ");

        assertTrue(policy.requiresAuthentication());
        assertTrue(policy.tokenMatches("exact-token"));
        assertFalse(policy.tokenMatches(null));
        assertFalse(policy.tokenMatches(""));
        assertFalse(policy.tokenMatches(" exact-token"));
        assertFalse(policy.tokenMatches("exact-token "));
        assertFalse(policy.tokenMatches("wrong-token"));
    }
}
