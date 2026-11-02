package com.centralArchiveSystem.Archive.common.dto;

import com.centralArchiveSystem.Archive.common.i18n.ArabicApiMessages;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final Meta meta;

    public ApiResponse(boolean success, T data, Meta meta) {
        this.success = success; this.data = data; this.meta = meta;
    }
    public boolean isSuccess() { return success; }
    public T getData() { return data; }
    public Meta getMeta() { return meta; }

    public static class Meta {
        private final String message;
        public Meta(String message) { this.message = ArabicApiMessages.translate(message); }
        public String getMessage() { return message; }
    }
    public static <T> ApiResponse<T> success(T data) { return new ApiResponse<>(true, data, null); }
    public static <T> ApiResponse<T> success(T data, String message) { return new ApiResponse<>(true, data, new Meta(message)); }
    public static <T> ApiResponse<T> success() { return new ApiResponse<>(true, null, null); }
    public static <T> ApiResponse<T> error(String message) { return new ApiResponse<>(false, null, new Meta(message)); }
    public static <T> ApiResponse<T> error(String message, T data) { return new ApiResponse<>(false, data, new Meta(message)); }
}
