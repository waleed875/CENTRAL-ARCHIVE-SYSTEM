package com.centralArchiveSystem.Archive;

import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationUpdateRequest;
import com.centralArchiveSystem.Archive.core.role.dto.RoleUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserUpdateRequest;
import com.centralArchiveSystem.Archive.import_.package_.export.export.ExportRequest;
import com.centralArchiveSystem.Archive.project.project.dto.ProjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InputValidationContractTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll static void closeFactory() { FACTORY.close(); }

    @Test void optionalPatchFieldsCanBeOmitted() {
        assertTrue(VALIDATOR.validate(new UserUpdateRequest(null, null, null, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new OrganizationUpdateRequest(null, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new RoleUpdateRequest(null, null, null)).isEmpty());
    }
    @Test void explicitBlankNamesAreRejected() {
        assertFalse(VALIDATOR.validate(new UserUpdateRequest(" ", null, null, null, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new OrganizationUpdateRequest("", null, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new RoleUpdateRequest("\t", null, null)).isEmpty());
    }
    @Test void invalidEmailIsRejectedOnCreateAndUpdate() {
        UserRequest create = new UserRequest("valid-user", "ValidPassword123!", "Valid User",
                "not-an-email", null, null, null);
        assertTrue(VALIDATOR.validate(create).stream().anyMatch(v -> v.getPropertyPath().toString().equals("email")));
        assertFalse(VALIDATOR.validate(new UserUpdateRequest(null, "a@", null, null, null)).isEmpty());
    }
    @Test void validAndOmittedEmailRemainSupported() {
        assertTrue(VALIDATOR.validate(new UserUpdateRequest(null, "person@example.test", null, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new UserUpdateRequest(null, "", null, null, null)).isEmpty());
    }
    @Test void nullExportListElementIsRejected() {
        assertFalse(VALIDATOR.validate(new ExportRequest(Arrays.asList((UUID) null))).isEmpty());
    }
    @Test void nullProjectMemberIsRejectedBeforeServiceInvocation() throws Exception {
        String body = """
            {"organizationId":"11111111-1111-4111-8111-111111111111", "projectNumber":"TEST",
             "name":"Test", "year":2026, "members":[null]}
            """;
        ProjectRequest request = new ObjectMapper().readValue(body, ProjectRequest.class);
        assertTrue(VALIDATOR.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().startsWith("members")));
    }
    @Test void nameLengthBoundaryIsValidated() {
        assertTrue(VALIDATOR.validate(new OrganizationUpdateRequest("x".repeat(200), null, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new OrganizationUpdateRequest("x".repeat(201), null, null)).isEmpty());
    }
}
