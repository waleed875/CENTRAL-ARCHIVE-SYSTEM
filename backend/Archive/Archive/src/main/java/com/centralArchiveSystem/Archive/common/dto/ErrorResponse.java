package com.centralArchiveSystem.Archive.common.dto;

import java.util.List;

public class ErrorResponse {
    private final String code;
    private final String message;
    private final List<Detail> details;

    public ErrorResponse(String code, String message, List<Detail> details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public static class Detail {
        private final String field;
        private final String message;
        public Detail(String field, String message) { this.field = field; this.message = message; }
        public String getField() { return field; }
        public String getMessage() { return message; }
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public List<Detail> getDetails() { return details; }
}
