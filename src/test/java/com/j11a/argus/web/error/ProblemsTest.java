package com.j11a.argus.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    @Test
    void aProblemWithoutAnyPropertiesHasNoCode() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        assertThat(Problems.hasCode(problem)).isFalse();
    }

    @Test
    void aProblemWithOtherPropertiesButNoCodeHasNoCode() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setProperty("errors", "x");

        assertThat(Problems.hasCode(problem)).isFalse();
    }

    @Test
    void aProblemBuiltFromAnErrorCodeCarriesIt() {
        assertThat(Problems.hasCode(Problems.of(ErrorCode.NOT_FOUND, "gone"))).isTrue();
    }
}
