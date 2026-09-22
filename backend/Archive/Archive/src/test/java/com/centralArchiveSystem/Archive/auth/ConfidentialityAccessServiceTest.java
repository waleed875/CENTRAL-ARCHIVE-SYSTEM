package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfidentialityAccessServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ConfidentialityLevelRepository repository = mock(ConfidentialityLevelRepository.class);
    private final ConfidentialityAccessService service =
            new ConfidentialityAccessService(currentUserService, repository);

    @Test
    void allowsUnclassifiedResourceWithoutClearance() {
        assertDoesNotThrow(() -> service.requireReadAccess(null));
    }

    @Test
    void allowsResourceAtOrBelowUserClearance() {
        UUID userLevelId = UUID.randomUUID();
        UUID resourceLevelId = UUID.randomUUID();

        User user = new User();
        user.setMaxConfidentialityLevelId(userLevelId);

        ConfidentialityLevel userLevel = new ConfidentialityLevel();
        userLevel.setId(userLevelId);
        userLevel.setLevelOrder(3);

        ConfidentialityLevel resourceLevel = new ConfidentialityLevel();
        resourceLevel.setId(resourceLevelId);
        resourceLevel.setLevelOrder(2);

        when(currentUserService.user()).thenReturn(user);
        when(repository.findById(userLevelId)).thenReturn(Optional.of(userLevel));
        when(repository.findById(resourceLevelId)).thenReturn(Optional.of(resourceLevel));

        assertDoesNotThrow(() -> service.requireReadAccess(resourceLevelId));
    }

    @Test
    void rejectsResourceAboveUserClearance() {
        UUID userLevelId = UUID.randomUUID();
        UUID resourceLevelId = UUID.randomUUID();

        User user = new User();
        user.setMaxConfidentialityLevelId(userLevelId);

        ConfidentialityLevel userLevel = new ConfidentialityLevel();
        userLevel.setId(userLevelId);
        userLevel.setLevelOrder(2);

        ConfidentialityLevel resourceLevel = new ConfidentialityLevel();
        resourceLevel.setId(resourceLevelId);
        resourceLevel.setLevelOrder(4);

        when(currentUserService.user()).thenReturn(user);
        when(repository.findById(userLevelId)).thenReturn(Optional.of(userLevel));
        when(repository.findById(resourceLevelId)).thenReturn(Optional.of(resourceLevel));

        assertThrows(
                AccessDeniedException.class,
                () -> service.requireReadAccess(resourceLevelId)
        );
    }

    @Test
    void rejectsClassifiedResourceWhenUserHasNoClearance() {
        UUID resourceLevelId = UUID.randomUUID();
        User user = new User();
        user.setMaxConfidentialityLevelId(null);

        when(currentUserService.user()).thenReturn(user);

        assertThrows(
                AccessDeniedException.class,
                () -> service.requireReadAccess(resourceLevelId)
        );
    }
}
