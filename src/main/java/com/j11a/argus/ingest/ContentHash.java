package com.j11a.argus.ingest;

import com.j11a.argus.crypto.Sha256;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class ContentHash {

    private ContentHash() {
    }

    public static String of(String title, @Nullable String excerpt, List<String> categories) {
        String normTitle = normalise(title);
        String normExcerpt = excerpt == null ? "" : normalise(excerpt);

        List<String> normCategories = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : categories) {
            String normCat = normalise(category);
            if (!normCat.isEmpty() && seen.add(normCat.toLowerCase(Locale.ROOT))) {
                normCategories.add(normCat);
            }
        }
        normCategories.sort(String.CASE_INSENSITIVE_ORDER);

        String payload = normTitle + '\u001F' + normExcerpt + '\u001F' + String.join("\u001E", normCategories);
        return HexFormat.of().formatHex(Sha256.digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String normalise(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String noNbsp = nfkc.replace('\u00A0', ' ');
        String collapsed = noNbsp.replaceAll("\\s+", " ");
        return collapsed.strip();
    }
}
