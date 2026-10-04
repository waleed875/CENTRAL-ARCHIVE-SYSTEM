package com.centralArchiveSystem.Archive.common.exception;

/**
 * A record the caller referred to by id that is not there.
 *
 * <p>Keeps the parts for the same reason as {@link ConflictException}: the handler needs the field to
 * put in the response's {@code details}, and an id on its own says which field failed only as prose.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resource;
    private final String field;
    private final transient Object value;

    public ResourceNotFoundException(String resource, String field, Object value) {
        super(String.format("%s not found with %s: '%s'", resource, field, value));
        this.resource = resource;
        this.field = field;
        this.value = value;
    }

    /** The entity that was not found, e.g. {@code Project}. */
    public String getResource() {
        return resource;
    }

    /** The field the lookup used, e.g. {@code id}. */
    public String getField() {
        return field;
    }

    /** The value that was looked up, or a description of it when the service could not. */
    public Object getValue() {
        return value;
    }
}
