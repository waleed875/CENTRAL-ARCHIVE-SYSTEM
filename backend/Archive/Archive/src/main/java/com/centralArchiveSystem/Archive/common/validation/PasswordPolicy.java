package com.centralArchiveSystem.Archive.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** BCrypt limits UTF-8 bytes, not Java characters. Requiredness remains @NotBlank. */
@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {
    String message() default "كلمة المرور يجب ألا تقل عن 8 أحرف وألا تتجاوز 72 بايت بترميز UTF-8";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
