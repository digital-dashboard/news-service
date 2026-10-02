package com.j11a.argus.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class Fixtures {

    private Fixtures() {
    }

    public static byte[] feed(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/feeds/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture feeds/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
