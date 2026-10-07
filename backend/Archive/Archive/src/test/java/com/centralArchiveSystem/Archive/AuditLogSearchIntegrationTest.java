package com.centralArchiveSystem.Archive;

import com.centralArchiveSystem.Archive.audit.AuditLog;
import com.centralArchiveSystem.Archive.audit.AuditLogRepository;
import com.centralArchiveSystem.Archive.audit.AuditLogService;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit log search, run against real PostgreSQL.
 *
 * <p>Every other repository filter in this codebase uses the same
 * `(:param IS NULL OR column = :param)` shape, and every other one is exercised by a test that
 * reaches the database. This one was not: {@code AuditAspectTest} mocks the service outright, and
 * the schema test only covers the write path. So the query was translated and compiled on every
 * build and never executed, and the audit log screen answered 500 for every caller while the suite
 * reported 264 passing tests.
 *
 * <p>That is the same shape of failure as the Jackson 3 mix-up elsewhere in this project: valid code
 * that compiles, and never runs. A repository query is not covered until something asks the database
 * for a row.
 */
@SpringBootTest
@Transactional
class AuditLogSearchIntegrationTest {

    /** The user the audit rows below are attributed to. audit_logs.user_id is a foreign key. */
    private static final String PROBE_USERNAME = "audit-search-probe";

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    AuditLogService auditLogService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    /**
     * Writes the rows these assertions are about, instead of looking for rows that some earlier
     * test happened to leave behind.
     *
     * <p>Until the suite was moved off the development database this class seeded nothing: it
     * asked the audit log for rows and asserted they were there. That held only when something
     * else had already written them into the same database, which is why it passed in CI (its
     * steps share one container database, so earlier steps filled the table) and passed locally
     * (the development database had collected rows from earlier runs), and failed the moment it
     * was pointed at an empty database of its own.
     *
     * <p>Rolled back rather than deleted, because the schema refuses a direct delete on this
     * table: {@code audit.audit_logs DELETE requires controlled retention purge mode}. That guard
     * is deliberate — an audit trail a test can quietly erase is not an audit trail — so the rows
     * are written and unwound inside the transaction the class already runs in.
     */
    @BeforeEach
    void seedAuditRows() {
        User probe = userRepository.findByUsername(PROBE_USERNAME).orElseGet(() -> {
            User created = new User();
            created.setUsername(PROBE_USERNAME);
            created.setPasswordHash(passwordEncoder.encode("never-used-in-this-test"));
            created.setFullName("Audit Search Probe");
            created.setEmail(PROBE_USERNAME + "@example.test");
            created.setStatus(User.UserStatus.ACTIVE);
            return userRepository.save(created);
        });

        // Staggered on purpose: the ordering assertion needs one timestamp per row, and a column
        // default of now() would give every row in this single transaction the same value.
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 9, 0);
        for (int i = 0; i < 25; i++) {
            AuditLog row = new AuditLog();
            // Only half the rows carry the user, so filtering by it has something to exclude.
            row.setUserId(i % 2 == 0 ? probe.getId() : null);
            row.setAction(i % 2 == 0 ? "READ" : "CREATE");
            row.setEntityType("AUDIT_SEARCH_SEED");
            row.setEntityId(UUID.randomUUID());
            row.setDescription("written by AuditLogSearchIntegrationTest");
            row.setCreatedAt(first.plusMinutes(i));
            auditLogRepository.save(row);
        }
        auditLogRepository.flush();
    }

    @Test
    void everyFilterNullReturnsRowsRatherThanFailing() {
        // The state the audit log screen opens in: no filters at all, just the first page. This is
        // the call that was failing, and it is the one a person makes first.
        var page = auditLogService.search(null, null, null, null, null, null, 0, 20);

        assertNotNull(page);
        assertTrue(page.getTotalElements() > 0, "the seeded audit rows have to be reachable");
        assertEquals(20, page.getContent().size());
        // Newest first, which is the order the screen renders and the reason the sort is in the query
        // rather than in the caller.
        assertTrue(page.getContent().get(0).createdAt().isAfter(page.getContent().get(1).createdAt()));
    }

    @Test
    void aSingleFilterStillNarrowsTheResult() {
        // One filter set, the rest null: the combination a person actually uses, and the one where
        // a per-parameter type problem would hide behind the all-null case above.
        var everything = auditLogService.search(null, null, null, null, null, null, 0, 100);
        assertFalse(everything.getContent().isEmpty(), "there is nothing to narrow");

        var action = everything.getContent().get(0).action();
        var narrowed = auditLogService.search(null, action, null, null, null, null, 0, 100);

        assertTrue(narrowed.getContent().stream().allMatch(row -> action.equals(row.action())));
    }

    @Test
    void theRepositoryQueryItselfRunsWithNulls() {
        // Called directly rather than through the service, so a failure here points at the query
        // instead of at the controller's page arithmetic.
        var page = auditLogRepository.findFiltered(null, null, null, null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertNotNull(page);
    }

    @Test
    void aUserIdFilterIsAcceptedAsAUuid() {
        // The one filter whose Java type is not a String, and the one where a null bind has no type
        // to fall back on. A real user, because `audit_logs.user_id` is a foreign key and a made-up
        // one would be rejected by the column before the filter was ever exercised.
        var user = userRepository.findByUsername(PROBE_USERNAME).orElseThrow();
        var page = auditLogService.search(user.getId(), null, null, null, null, null, 0, 20);

        assertNotNull(page);
        assertTrue(page.getContent().stream().allMatch(row -> user.getId().equals(row.userId())));
    }

    @Test
    void dateRangeFiltersRun() {
        // LocalDateTime bounds, the other two parameters with no String form to lean on.
        var from = auditLogService.search(null, null, null, null,
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0), null, 0, 20);
        assertNotNull(from);

        var until = auditLogService.search(null, null, null, null, null,
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0), 0, 20);
        assertEquals(0, until.getTotalElements());
    }

    /**
     * Rolled back rather than deleted, because the schema refuses a direct delete on this table:
     * {@code audit.audit_logs DELETE requires controlled retention purge mode}. That guard is
     * deliberate — an audit trail that a test can quietly erase is not an audit trail — so the row
     * is written and unwound inside a transaction instead.
     */
    @Test
    @Transactional
    void theRowTheSeedWroteIsTheRowTheSearchReturns() {
        // Written through the entity and read back through the filter, so the two halves of this
        // feature are known to agree rather than each being separately plausible.
        //
        // `audit_logs.user_id` is a foreign key to `core.users`, so this needs a real user rather
        // than a fresh UUID: the column rejects a made-up one, which is the schema doing its job.
        var user = userRepository.findByUsername(PROBE_USERNAME).orElseThrow();
        var marker = UUID.randomUUID();
        AuditLog row = new AuditLog();
        row.setUserId(user.getId());
        row.setAction("READ");
        row.setEntityType("AUDIT_SEARCH_PROBE");
        row.setEntityId(marker);
        row.setDescription("written by AuditLogSearchIntegrationTest");
        auditLogRepository.saveAndFlush(row);

        var byUser = auditLogService.search(user.getId(), null, null, null, null, null, 0, 200);
        assertTrue(byUser.getContent().stream().anyMatch(entry -> "AUDIT_SEARCH_PROBE".equals(entry.entityType())));
        assertTrue(byUser.getContent().stream().allMatch(entry -> user.getId().equals(entry.userId())));

        var byEntity = auditLogService.search(null, null, null, marker, null, null, 0, 50);
        assertEquals(1, byEntity.getTotalElements(), "the entityId filter has to find the one row");

        // Narrowed by two filters at once, which is the combination a person actually builds up and
        // the one that would break if a cast were missing from any single parameter.
        var both = auditLogService.search(user.getId(), "READ", null, null, null, null, 0, 200);
        assertTrue(both.getContent().stream().allMatch(entry -> "READ".equals(entry.action())));
    }
}
