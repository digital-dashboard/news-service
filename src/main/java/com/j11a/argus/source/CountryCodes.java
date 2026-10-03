package com.j11a.argus.source;

import com.j11a.argus.web.error.ApiException;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class CountryCodes {

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final String COUNTRY_FIELD = "country";
    private static final String INVALID_COUNTRY_MESSAGE = "must be an ISO 3166-1 alpha-2 code";

    private CountryCodes() {
    }

    public static String normalise(@Nullable String raw) {
        if (raw == null) {
            throw ApiException.validationFailed(COUNTRY_FIELD, INVALID_COUNTRY_MESSAGE);
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(upper)) {
            throw ApiException.validationFailed(COUNTRY_FIELD, INVALID_COUNTRY_MESSAGE);
        }
        return upper;
    }
}
