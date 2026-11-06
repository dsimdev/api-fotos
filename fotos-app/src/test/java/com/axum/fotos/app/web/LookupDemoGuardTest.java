package com.axum.fotos.app.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The demo has no authentication: it must refuse to start unless the server listens on this machine only. */
class LookupDemoGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "LOCALHOST", "::1", "[::1]", " 127.0.0.1 "})
    void startsWhenTheServerListensOnLoopbackOnly(String address) {
        assertDoesNotThrow(() -> LookupDemoController.requireLoopback(address));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0.0.0.0", "::", "192.168.1.5", "10.0.0.7", "127.0.0.1.evil.example", "example.com"})
    void refusesToStartOnAnyOtherAddress(String address) {
        var e = assertThrows(IllegalStateException.class, () -> LookupDemoController.requireLoopback(address));
        assertTrue(e.getMessage().contains("SERVER_ADDRESS=127.0.0.1"), "the message must say how to fix it");
    }

    @Test
    void anEmptyAddressMeansEveryInterfaceAndIsRefused() {
        // Without server.address Tomcat listens on all interfaces: this is the case the guard exists for.
        assertThrows(IllegalStateException.class, () -> LookupDemoController.requireLoopback(""));
    }
}
