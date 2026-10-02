package com.j11a.argus.feed.api;

import com.j11a.argus.url.HttpUrls;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AbsoluteHttpUrl.Validator.class)
public @interface AbsoluteHttpUrl {

    String message() default "must be an absolute http or https URL without user-info";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null passes; pair with @NotBlank when the value is required. */
    class Validator implements ConstraintValidator<AbsoluteHttpUrl, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank()
                    || HttpUrls.parseHttp(value).filter(uri -> !HttpUrls.hasUserInfo(uri)).isPresent();
        }
    }
}
