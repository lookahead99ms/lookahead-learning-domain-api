package com.lookahead.domain.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The one digest helper every stored fingerprint, token digest and release check goes through. */
class Sha256Test {
    @Test void bytesBecomeTheLowerCaseHexDigest() {
        assertThat(Sha256.hex(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256.hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test void textIsDigestedAsItsUtf8Bytes() {
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex("é")).isEqualTo(Sha256.hex(new byte[] {(byte) 0xC3, (byte) 0xA9})).isNotEqualTo(Sha256.hex(new byte[] {(byte) 0xE9}));
    }
}
