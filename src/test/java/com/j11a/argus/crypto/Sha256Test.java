package com.j11a.argus.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Sha256Test {

    @Test
    void digestsTheKnownVectorForAbc() {
        byte[] digest = Sha256.digest("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void digestsEmptyInputToTheKnownEmptyDigest() {
        assertThat(HexFormat.of().formatHex(Sha256.digest(new byte[0])))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
