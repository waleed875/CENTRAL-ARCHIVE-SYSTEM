package com.centralArchiveSystem.Archive.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public final class PasswordPolicyValidator implements ConstraintValidator<PasswordPolicy, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || (!value.isBlank()
                && value.codePointCount(0, value.length()) >= 8
                && value.getBytes(StandardCharsets.UTF_8).length <= 72);
    }
}
