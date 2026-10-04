package com.centralArchiveSystem.Archive.common.exception;

/**
 * A value that is already taken, on a field the caller filled in.
 *
 * <p>The resource, field and value are kept rather than only being formatted into the message,
 * because the message is the part a client reads and the parts are the part a client acts on: the
 * error contract promises a field name, and a field name cannot be recovered from a sentence without
 * parsing English prose out of it. Adding the getters changes no call site.
 */
public class ConflictException extends RuntimeException {

    private final String resource;
    private final String field;
    private final transient Object value;

    public ConflictException(String resource, String field, Object value) {
        super(String.format("%s already exists with %s: '%s'", resource, field, value));
        this.resource = resource;
        this.field = field;
        this.value = value;
    }

    /** The entity the clash is on, e.g. {@code User}. */
    public String getResource() {
        return resource;
    }

    /** The request field the caller filled in, e.g. {@code username}. */
    public String getField() {
        return field;
    }

    /** The value that clashed. Null when the service could not name one. */
    public Object getValue() {
        return value;
    }
}
