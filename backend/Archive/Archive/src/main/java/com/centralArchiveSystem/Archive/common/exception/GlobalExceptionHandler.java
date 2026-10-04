package com.centralArchiveSystem.Archive.common.exception;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.EntityNotFoundException;
import tools.jackson.core.JacksonException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<ApiResponse<ErrorResponse>> build(HttpStatus status,
                                                             String code,
                                                             String message,
                                                             List<ErrorResponse.Detail> details) {
        ErrorResponse body = new ErrorResponse(code, message, details);
        return ResponseEntity.status(status).body(ApiResponse.error(message, body));
    }

    /**
     * A duplicate, named by field.
     *
     * <p>This is the path a person actually hits for a taken value, not the database constraint one.
     * The services check first and throw this, and a check that loses a race falls through to the
     * constraint, so both have to render the same way or the message changes shape depending on
     * timing.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleConflict(ConflictException ex) {
        String field = ex.getField();
        String label = FieldErrorMessages.label(ex.getResource(), field);
        // Quoting the value is what saves the reader hunting through their own list for the entry
        // that collided, but only a field that is not a secret may be sent back.
        boolean quote = ex.getValue() != null && FieldErrorMessages.isQuotable(field);
        String message = quote
                ? label + " «" + ex.getValue() + "» مستخدم من قبل"
                : label + " مستخدم من قبل";
        return build(HttpStatus.CONFLICT, "CONFLICT", message,
                field == null ? null : List.of(new ErrorResponse.Detail(field, message)));
    }

    /**
     * A record that is not there, named by field. The id is not quoted back: the caller sent it, so
     * echoing a uuid tells them nothing they did not already have, while a named field such as an
     * email is worth quoting because they may have mistyped it.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleResourceNotFound(ResourceNotFoundException ex) {
        String field = ex.getField();
        String label = FieldErrorMessages.label(ex.getResource(), field);
        boolean quote = ex.getValue() != null && FieldErrorMessages.isQuotable(field);
        String message = quote
                ? label + " «" + ex.getValue() + "» غير موجود"
                : label + " غير موجود";
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", message,
                field == null ? null : List.of(new ErrorResponse.Detail(field, message)));
    }

    /**
     * A record the persistence layer reported as missing.
     *
     * <p>These carry an English sentence written at the throw site, and there are nineteen of them,
     * all of the shape {@code "<Entity> not found with id: " + id}. Rewriting all nineteen is a
     * wider change than this error-contract work warrants, so the entity is recovered from the
     * sentence instead: without it the reader is told only that some record is missing, and has no
     * way to know which one. The id is not quoted, for the same reason as everywhere else — the
     * caller sent it.
     */
    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class})
    public ResponseEntity<ApiResponse<ErrorResponse>> handleEntityNotFound(RuntimeException ex) {
        log.warn("Lookup found nothing: {}", ex.getMessage());
        String resource = entityInSentence(ex.getMessage());
        String message = resource == null
                ? arabic(ex.getMessage(), null)
                : FieldErrorMessages.resourceLabel(resource) + " غير موجود";
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }

    /**
     * The entity named in an English "not found" sentence, e.g. {@code "File not found with id: 3"}
     * to {@code File}, or {@code null} when the sentence is not of that shape. The id at the end is
     * deliberately not returned: it is a value the caller already has and an id is never useful in
     * a sentence a person reads.
     */
    private static String entityInSentence(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^([A-Z][A-Za-z]+)\\s+not found\\b")
                .matcher(message.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Database integrity conflict", ex);
        String sql = deepestMessage(ex);
        String constraint = FieldErrorMessages.findConstraint(sql);
        String field = constraint == null ? null : FieldErrorMessages.fieldForConstraint(constraint);
        if (field == null) {
            // A constraint the caller never filled in, such as created_by. Naming a field they did
            // not send would be worse than not naming one, so the field is left out and the
            // constraint goes to the log only.
            field = FieldErrorMessages.findColumn(sql);
        }
        if (field == null) {
            return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT",
                    "البيانات المُدخلة تتعارض مع بيانات موجودة أو مع سجل مُشار إليه", null);
        }
        String message = describeConflict(sql, constraint, field);
        return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", message,
                List.of(new ErrorResponse.Detail(field, message)));
    }

    /**
     * The Arabic sentence for a refused write. The wording follows which constraint refused it, so a
     * value that is already taken is not described the same way as a reference that does not exist.
     */
    private static String describeConflict(String sql, String constraint, String field) {
        String label = FieldErrorMessages.labelForConstraint(constraint, field);
        return switch (FieldErrorMessages.kindOfConstraint(sql)) {
            case DUPLICATE -> {
                String column = FieldErrorMessages.findKeyColumn(sql);
                String value = FieldErrorMessages.isQuotable(column)
                        ? FieldErrorMessages.findKeyValue(sql) : null;
                yield value == null
                        ? label + " مستخدم من قبل"
                        : label + " «" + value + "» مستخدم من قبل";
            }
            case MISSING_REFERENCE -> "قيمة " + label + " غير صحيحة: السجل المُشار إليه غير موجود";
            case MISSING_VALUE -> "حقل " + label + " مطلوب";
            case NOT_ALLOWED_VALUE -> {
                String allowed = FieldErrorMessages.allowedValuesFor(constraint);
                yield allowed == null
                        ? "قيمة " + label + " غير صالحة"
                        : "قيمة " + label + " غير صالحة. القيم المقبولة: " + allowed;
            }
            case UNKNOWN -> "قيمة " + label + " تتعارض مع بيانات موجودة";
        };
    }

    /**
     * The whole exception chain as one string. PostgreSQL splits a failure across the primary message
     * and a DETAIL line, and the DETAIL is often on a nested cause rather than the exception Spring
     * caught, so every cause is folded in before the message is searched.
     */
    private static String deepestMessage(Throwable ex) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "قيمة المعامل '" + ex.getName() + "' غير صالحة";
        return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleMissingRequestParameter(
            MissingServletRequestParameterException ex) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "المعامل المطلوب مفقود: " + ex.getParameterName(),
                null
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // Jackson names the property it stopped at, and that name is the whole answer for whoever is
        // filling in the form. A body that is not JSON at all has no such name, and saying "الحقل
        // المطلوب" there would point at a field that is not there.
        String field = firstJsonField(ex);
        if (field == null) {
            return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                    "نص الطلب غير صالح: تعذّرت قراءته كبيانات JSON", null);
        }
        String message = "قيمة " + FieldErrorMessages.labelForJsonPath(field) + " غير صالحة";
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", message,
                List.of(new ErrorResponse.Detail(field, message)));
    }

    /**
     * The JSON path Jackson stopped at, or {@code null} when the body was not parseable at all.
     *
     * <p>The type here is Jackson 3's, which is what Spring's message converter throws. Jackson 2
     * is also on the classpath, because the application autowires an {@code ObjectMapper} of its
     * own, and its exception is a different class with a different accessor name — matching the
     * wrong one compiles, never fires, and returns null here, which is the same as having no field
     * reporting at all.
     *
     * <p>The whole path is rebuilt rather than just the failing property's name, because a property
     * name alone cannot say which row of a list it was: two members with a missing role both report
     * {@code roleName}, and a form can only mark the right one if it is told it was the second. The
     * result is {@code members[1].role_name}, indices bracketed so the two readings cannot be
     * confused with one another.
     */
    private static String firstJsonField(HttpMessageNotReadableException ex) {
        for (Throwable current = ex.getCause(); current != null; current = current.getCause()) {
            if (current instanceof JacksonException mapping) {
                String path = joinPath(mapping.getPath());
                if (path != null) {
                    return path;
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * Jackson's references as a client can act on them: a property, dotted to the next segment, with
     * an index in brackets. A reference that is neither a property nor an index is written as
     * whatever it points at, so no segment is silently dropped on the way out.
     */
    private static String joinPath(List<JacksonException.Reference> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getIndex() >= 0) {
                joined.append('[').append(reference.getIndex()).append(']');
            } else if (reference.getPropertyName() != null) {
                if (joined.length() > 0) {
                    joined.append('.');
                }
                joined.append(reference.getPropertyName());
            } else if (reference.from() != null) {
                joined.append('[').append(reference.from()).append(']');
            }
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleBusinessRule(BusinessRuleException ex) {
        // The rule messages on the request paths are written in Arabic at the throw site, so this
        // passes them straight through. The ones still in English are operator-level failures, and
        // those go to the log rather than to a person filling in a form.
        log.warn("Business rule rejected the request: {}", ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                arabic(ex.getMessage(), null), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleNoResourceFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "المسار المطلوب غير موجود", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.Detail> details = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorResponse.Detail(
                        fieldError.getField(), arabic(fieldError.getDefaultMessage(), fieldError.getField())))
                .forEach(details::add);
        // A constraint on the record itself, rather than on one of its components, lands in
        // getGlobalErrors and not in getFieldErrors. Reading only the latter dropped it, and the
        // caller got a 400 whose details array was empty and so said nothing about what was wrong.
        ex.getBindingResult().getGlobalErrors().stream()
                .map(globalError -> new ErrorResponse.Detail(
                        "", arabic(globalError.getDefaultMessage(), "")))
                .forEach(details::add);

        // With one bad field the headline becomes that field's own sentence, so a single mistake
        // reads as one mistake rather than as a failure plus a list to cross-reference. With several
        // it stays a count, because no one sentence can name them all.
        String headline = details.size() == 1
                ? details.get(0).getMessage()
                : "الحقول التالية غير صالحة (" + details.size() + ")";
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", headline, details);
    }

    /**
     * A message in Arabic, naming the field when one is known.
     *
     * <p>A message that is already Arabic passes through untouched. An English one does not: the
     * response gets a plain Arabic sentence and the original goes to the log, because a half
     * translated error that embeds the English in brackets is worse to read than a short Arabic
     * sentence, and the English was never going to help the person reading it. The validator's own
     * default is ASCII, which is the case this exists for.
     */
    private static String arabic(String message, String field) {
        if (message != null && !message.isBlank() && !message.matches("[\\x00-\\x7F]+")) {
            return message;
        }
        String label = field == null || field.isBlank()
                ? "البيانات المُدخلة"
                : FieldErrorMessages.label(field);
        return "قيمة " + label + " غير صالحة";
    }

    /**
     * A rejected argument.
     *
     * <p>The one case worth special handling is a bad enum value. {@code valueOf} answers with
     * {@code No enum constant com.centralArchiveSystem...OrganizationType.MINISTRY}, which tells a
     * person filling in a form nothing and tells them the internal package layout instead. The
     * allowed set is the answer they can act on, and substituting it also keeps the class name in
     * the response.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleIllegalArgument(IllegalArgumentException ex) {
        String allowed = FieldErrorMessages.enumValuesFor(ex.getMessage());
        if (allowed != null) {
            String field = FieldErrorMessages.fieldForEnum(ex.getMessage());
            String message = "قيمة " + FieldErrorMessages.label(field) + " غير صالحة. القيم المقبولة: " + allowed;
            return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message,
                    field == null ? null : List.of(new ErrorResponse.Detail(field, message)));
        }
        log.warn("Rejected as a bad argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", arabic(ex.getMessage(), null), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleIllegalState(IllegalStateException ex) {
        log.warn("Rejected as an invalid state: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "INVALID_STATE", arabic(ex.getMessage(), null), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "لا تملك صلاحية تنفيذ هذا الإجراء", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "حدث خطأ غير متوقع في الخادم", null);
    }
}
