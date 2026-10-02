package com.j11a.argus.web.error;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

final class Problems {

    static final String CODE_PROPERTY = "code";
    static final String ERRORS_PROPERTY = "errors";

    private Problems() {
    }

    static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        applyContract(problem, code);
        return problem;
    }

    static ResponseEntity<ProblemDetail> response(ErrorCode code, String detail) {
        return ResponseEntity.status(code.status()).body(of(code, detail));
    }

    static void applyContract(ProblemDetail problem, ErrorCode code) {
        problem.setTitle(code.title());
        problem.setType(URI.create(code.typeUri()));
        problem.setProperty(CODE_PROPERTY, code.name());
    }

    static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey(CODE_PROPERTY);
    }
}
