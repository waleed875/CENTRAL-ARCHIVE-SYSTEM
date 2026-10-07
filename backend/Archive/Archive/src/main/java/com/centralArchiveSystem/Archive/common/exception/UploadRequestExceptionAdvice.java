package com.centralArchiveSystem.Archive.common.exception;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Handles client upload contract errors before the generic server-error advice. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class UploadRequestExceptionAdvice {
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> missingPart(MissingServletRequestPartException ex) {
        String message = "جزء مطلوب من طلب الرفع مفقود: " + ex.getRequestPartName();
        return ResponseEntity.badRequest().body(ApiResponse.error(message,
                new ErrorResponse("VALIDATION_FAILED", message, null)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        String message = "نوع محتوى الطلب غير مدعوم لهذه العملية";
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResponse.error(message,
                new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", message, null)));
    }
}
