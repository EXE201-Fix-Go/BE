package com.fixgo.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {
    @Test
    void ignoresTheHeaderWhenNoProxyIsTrusted() {
        assertThat(ClientIp.resolve("6.6.6.6", "10.0.0.1", 0)).isEqualTo("10.0.0.1");
    }

    @Test
    void takesTheEntryAddedByOurOwnProxyNotTheOneTheClientSent() {
        // The client forged "6.6.6.6"; our single proxy appended the address it really saw.
        assertThat(ClientIp.resolve("6.6.6.6, 203.0.113.9", "10.0.0.1", 1)).isEqualTo("203.0.113.9");
        assertThat(ClientIp.resolve("6.6.6.6, 203.0.113.9, 172.16.0.2", "10.0.0.1", 2)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToTheSocketAddressWhenTheHeaderIsMissingOrShort() {
        assertThat(ClientIp.resolve(null, "10.0.0.1", 1)).isEqualTo("10.0.0.1");
        assertThat(ClientIp.resolve("  ", "10.0.0.1", 1)).isEqualTo("10.0.0.1");
        assertThat(ClientIp.resolve("203.0.113.9", "10.0.0.1", 2)).isEqualTo("10.0.0.1");
    }
}
