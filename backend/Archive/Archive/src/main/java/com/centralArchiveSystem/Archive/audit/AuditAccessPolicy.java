package com.centralArchiveSystem.Archive.audit;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Historical snapshots may contain an earlier classification; fail closed at top clearance. */
@Component("auditAccessPolicy")
public class AuditAccessPolicy {
    private final ConfidentialityAccessService confidentiality;
    private final ConfidentialityLevelRepository levels;

    public AuditAccessPolicy(ConfidentialityAccessService confidentiality, ConfidentialityLevelRepository levels) {
        this.confidentiality = confidentiality;
        this.levels = levels;
    }

    @Transactional(readOnly = true)
    public boolean canReadAudit() {
        int highest = levels.findAllByOrderByLevelOrderAsc().stream()
                .mapToInt(ConfidentialityLevel::getLevelOrder).max().orElse(0);
        Integer clearance = confidentiality.currentMaxLevelOrder();
        return highest == 0 || (clearance != null && clearance >= highest);
    }
}
