package com.j11a.argus.web.error;

import org.jspecify.annotations.Nullable;

public record FieldProblem(String field, @Nullable String message) {
}
