package com.j11a.argus.feed.parse;

import com.j11a.argus.feed.parse.FeedParseException.Reason;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.ParsingFeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Bytes to ROME's feed model, with every failure turned into a typed {@link FeedParseException}.
 *
 * <p>DOCTYPE declarations are allowed so RSS 0.91 feeds with the Netscape DOCTYPE parse. External entities and DTDs are
 * never loaded, and the JDK's entity-expansion limit stops billion-laughs documents; FeedParserTest asserts both.
 */
final class FeedReader {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final int HTML_SNIFF_BYTES = 1024;

    private FeedReader() {
    }

    static SyndFeed read(byte[] body, @Nullable String contentType) throws FeedParseException {
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body), contentType, true)) {
            if (declaresUtf8(reader) && !isValidUtf8(body)) {
                // A UTF-8 declaration over windows-1252 bytes is common; a character stream ignores the declaration.
                return newInput().build(new StringReader(new String(body, WINDOWS_1252)));
            }
            return newInput().build(reader);
        } catch (ParsingFeedException e) {
            throw new FeedParseException(looksLikeHtml(body) ? Reason.NOT_A_FEED : Reason.MALFORMED_XML);
        } catch (FeedException | IllegalArgumentException e) {
            throw new FeedParseException(Reason.NOT_A_FEED);
        } catch (IOException e) {
            throw new FeedParseException(Reason.MALFORMED_XML);
        }
    }

    private static SyndFeedInput newInput() {
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(true);
        input.setPreserveWireFeed(true);
        return input;
    }

    private static boolean declaresUtf8(XmlReader reader) {
        return StandardCharsets.UTF_8.name().equalsIgnoreCase(reader.getEncoding());
    }

    private static boolean isValidUtf8(byte[] body) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean looksLikeHtml(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, HTML_SNIFF_BYTES), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.contains("<!doctype html") || head.contains("<html");
    }
}
