package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ConfidentialityAccessService {

    private final CurrentUserService currentUserService;
    private final ConfidentialityLevelRepository confidentialityLevelRepository;

    public ConfidentialityAccessService(CurrentUserService currentUserService,
                                        ConfidentialityLevelRepository confidentialityLevelRepository) {
        this.currentUserService = currentUserService;
        this.confidentialityLevelRepository = confidentialityLevelRepository;
    }

    @Transactional(readOnly = true)
    public Integer currentMaxLevelOrder() {
        User user = currentUserService.user();
        UUID maxLevelId = user.getMaxConfidentialityLevelId();

        if (maxLevelId == null) {
            return null;
        }

        return confidentialityLevelRepository.findById(maxLevelId)
                .map(ConfidentialityLevel::getLevelOrder)
                .orElseThrow(() -> new AccessDeniedException(
                        "User confidentiality clearance is not configured correctly"));
    }

    @Transactional(readOnly = true)
    public void requireReadAccess(UUID confidentialityLevelId) {
        if (confidentialityLevelId == null) {
            return;
        }

        Integer maxLevelOrder = currentMaxLevelOrder();
        if (maxLevelOrder == null) {
            throw new AccessDeniedException("Confidentiality clearance is required");
        }

        ConfidentialityLevel resourceLevel = confidentialityLevelRepository.findById(confidentialityLevelId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Resource confidentiality level is not configured correctly"));

        if (resourceLevel.getLevelOrder() > maxLevelOrder) {
            throw new AccessDeniedException("Resource confidentiality level exceeds user clearance");
        }
    }

    @Transactional(readOnly = true)
    public boolean canRead(UUID confidentialityLevelId) {
        if (confidentialityLevelId == null) {
            return true;
        }

        Integer maxLevelOrder = currentMaxLevelOrder();
        if (maxLevelOrder == null) {
            return false;
        }

        return confidentialityLevelRepository.findById(confidentialityLevelId)
                .map(level -> level.getLevelOrder() <= maxLevelOrder)
                .orElse(false);
    }
}
