package com.centralArchiveSystem.Archive.common.exception;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void entityNotFoundExceptionsReturn404() {
        var jpaResponse = handler.handleEntityNotFound(
                new EntityNotFoundException("Organization not found"));
        assertEquals(HttpStatus.NOT_FOUND, jpaResponse.getStatusCode());
        assertNotNull(jpaResponse.getBody());
        assertFalse(jpaResponse.getBody().isSuccess());
        assertEquals("NOT_FOUND", jpaResponse.getBody().getData().getCode());

        var collectionResponse = handler.handleEntityNotFound(
                new NoSuchElementException("Import operation not found"));
        assertEquals(HttpStatus.NOT_FOUND, collectionResponse.getStatusCode());
        assertNotNull(collectionResponse.getBody());
        assertEquals("NOT_FOUND", collectionResponse.getBody().getData().getCode());
    }

    @Test
    void databaseIntegrityViolationsReturnConflictWithoutLeakingSqlDetails() {
        var response = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "insert violates foreign key constraint secret_internal_constraint"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("DATA_INTEGRITY_CONFLICT", response.getBody().getData().getCode());
        assertEquals(
                "The request conflicts with existing or referenced data",
                response.getBody().getData().getMessage());
        assertFalse(response.getBody().getData().getMessage().contains("constraint"));
    }
}
