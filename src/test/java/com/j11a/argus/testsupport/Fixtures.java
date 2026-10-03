package com.j11a.argus.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Fixtures {

    private Fixtures() {
    }

    /** A feed with a title and a site link and no entries: the smallest thing that parses and ingests. */
    public static byte[] emptyRss(String siteLink) {
        return ("<rss><channel><title>T</title><link>" + siteLink + "</link></channel></rss>")
                .getBytes(StandardCharsets.UTF_8);
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
