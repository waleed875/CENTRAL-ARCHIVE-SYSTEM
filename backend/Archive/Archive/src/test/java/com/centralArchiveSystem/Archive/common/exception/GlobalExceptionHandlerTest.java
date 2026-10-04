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
        assertFalse(collectionResponse.getBody().isSuccess());
        assertEquals("NOT_FOUND", collectionResponse.getBody().getData().getCode());
    }

    /**
     * The property that matters most here: a constraint the caller never touched must not reach
     * them. {@code created_by} is set by the server, so there is no field of theirs to name, and
     * telling them the internal constraint name would be both useless and a small disclosure.
     */
    @Test
    void anUnknownConstraintNamesNoFieldAndLeaksNoConstraintName() {
        var response = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "insert or update on table \"projects\" violates foreign key constraint "
                        + "\"projects_created_by_fkey\""));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        String message = response.getBody().getData().getMessage();
        assertFalse(message.contains("projects_created_by_fkey"));
        assertFalse(message.contains("constraint"));
        assertFalse(message.contains("projects"));
        assertTrue(message.matches("[\\u0600-\\u06FF\\s]+.*"), "the message should still be Arabic");
        assertNull(response.getBody().getData().getDetails());
    }

    /**
     * The case the whole change is about. PostgreSQL already says which column collided and what the
     * value was; the point is that both survive into the response, in Arabic, naming the field.
     */
    @Test
    void aDuplicateNamesTheFieldAndQuotesTheCollidingValue() {
        var response = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"organizations_code_key\"\n"
                        + "DETAIL: Key (code)=(و-خ-001) already exists."));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("رمز المؤسسة «و-خ-001» مستخدم من قبل",
                response.getBody().getData().getMessage());

        var details = response.getBody().getData().getDetails();
        assertEquals(1, details.size());
        assertEquals("code", details.get(0).getField());
    }

    /**
     * A duplicate and a missing reference are both a 409, so a message that did not tell them apart
     * would leave the reader unsure which mistake they made.
     */
    @Test
    void aMissingReferenceIsNotDescribedAsADuplicate() {
        var response = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "insert or update on table \"users\" violates foreign key constraint "
                        + "\"users_organization_id_fkey\"\n"
                        + "DETAIL: Key (organization_id)=(11111111-1111-1111-1111-111111111111) "
                        + "is not present in table \"organizations\"."));

        String message = response.getBody().getData().getMessage();
        assertEquals("قيمة المؤسسة غير صحيحة: السجل المُشار إليه غير موجود", message);
        assertFalse(message.contains("مستخدم من قبل"));
    }

    /**
     * A check constraint exists to enforce a fixed set, so the useful answer is the set, not just
     * that the value was wrong.
     */
    @Test
    void aRejectedEnumValueListsTheAllowedValues() {
        var response = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "new row for relation \"organizations\" violates check constraint "
                        + "\"organizations_type_check\""));

        String message = response.getBody().getData().getMessage();
        assertTrue(message.contains("نوع المؤسسة"));
        assertTrue(message.contains("COMPANY"));
        assertTrue(message.contains("DEPARTMENT"));
    }

    /** A secret must never be quoted back, however useful quoting the value is elsewhere. */
    @Test
    void aNonSecretColumnIsNeverQuotedBack() {
        assertTrue(FieldErrorMessages.isQuotable("code"));
        assertTrue(FieldErrorMessages.isQuotable("username"));
        assertFalse(FieldErrorMessages.isQuotable("password_hash"));
        assertFalse(FieldErrorMessages.isQuotable("full_name"));
        assertFalse(FieldErrorMessages.isQuotable("project_uid"));
    }
}
