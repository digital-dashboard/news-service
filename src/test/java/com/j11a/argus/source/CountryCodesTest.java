package com.j11a.argus.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import com.j11a.argus.web.error.FieldProblem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CountryCodesTest {

    @Test
    void lowercaseCodeIsUppercased() {
        assertThat(CountryCodes.normalise("gb")).isEqualTo("GB");
    }

    @Test
    void zambiaIsAcceptedInEitherCase() {
        assertThat(CountryCodes.normalise("ZM")).isEqualTo("ZM");
        assertThat(CountryCodes.normalise("zm")).isEqualTo("ZM");
    }

    @Test
    void ukIsRejectedBecauseIsoUsesGb() {
        assertValidationFailed("UK");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "USA", "12", "XX", "\u00DF", "\u0131d", "G\u00DF", "g b", "\uFF27\uFF22"})
    void invalidCountryCodesAreRejected(String invalid) {
        assertValidationFailed(invalid);
    }

    @SuppressWarnings("unchecked")
    private static void assertValidationFailed(String raw) {
        assertThatThrownBy(() -> CountryCodes.normalise(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    List<FieldProblem> errors = (List<FieldProblem>) apiEx.properties().get("errors");
                    assertThat(errors).isNotNull();
                    assertThat(errors).extracting(FieldProblem::field).containsExactly("country");
                    assertThat(errors).extracting(FieldProblem::message)
                            .containsExactly("must be an ISO 3166-1 alpha-2 code");
                });
    }
}
