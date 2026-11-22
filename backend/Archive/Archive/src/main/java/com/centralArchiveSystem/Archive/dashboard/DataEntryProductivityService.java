package com.centralArchiveSystem.Archive.dashboard;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProjectRow;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProductivityResponse;
import com.centralArchiveSystem.Archive.dashboard.dto.DataEntryProductivityResponse.UserProjects;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Data-entry performance reporting scoped to the requesting administrator's
 * confidentiality clearance. Legacy migrated and imported package projects
 * are not counted as manually created projects.
 */
@Service
@Transactional(readOnly = true)
public class DataEntryProductivityService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ConfidentialityAccessService access;
    private final UserRepository users;

    public DataEntryProductivityService(NamedParameterJdbcTemplate jdbc,
                                        ConfidentialityAccessService access,
                                        UserRepository users) {
        this.jdbc = jdbc;
        this.access = access;
        this.users = users;
    }

    private static final String VISIBLE_LATEST = """
        FROM project.projects original
        JOIN project.projects latest
          ON latest.project_uid = original.project_uid
         AND latest.is_latest_version = true
         AND latest.discarded_at IS NULL
        WHERE original.version_no = 1
          AND original.source_type = 'MANUAL'
          AND (latest.confidentiality_level_id IS NULL OR
              (:maxLevel IS NOT NULL AND EXISTS (
                SELECT 1 FROM core.confidentiality_levels level
                WHERE level.id = latest.confidentiality_level_id
                  AND level.level_order <= :maxLevel)))
        """;

    /**
     * Includes current data-entry users with zero projects and disabled users.
     * Deleted users lose their roles at anonymization, so their former role
     * cannot be established from current membership and they are not listed.
     */
    public DataEntryProductivityResponse summary() {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("maxLevel", access.currentMaxLevelOrder());

        List<UserProjects> rows = jdbc.query("""
            WITH attributed AS (
              SELECT original.created_by AS creator_id,
                     latest.project_uid, latest.status
              """ + VISIBLE_LATEST + """
            )
            SELECT u.id AS user_id, u.username, u.full_name, u.status,
              COUNT(DISTINCT a.project_uid) AS projects,
              COUNT(DISTINCT a.project_uid) FILTER
                  (WHERE a.status IN ('DRAFT','NEEDS_CORRECTION')) AS drafts,
              COUNT(DISTINCT a.project_uid) FILTER
                  (WHERE a.status = 'UNDER_REVIEW') AS review,
              COUNT(DISTINCT a.project_uid) FILTER
                  (WHERE a.status = 'APPROVED') AS approved,
              COUNT(DISTINCT a.project_uid) FILTER
                  (WHERE a.status = 'ARCHIVED') AS archived,
              COUNT(DISTINCT a.project_uid) FILTER
                  (WHERE a.status NOT IN
                   ('DRAFT','NEEDS_CORRECTION','UNDER_REVIEW','APPROVED','ARCHIVED')) AS other
            FROM core.users u
            JOIN core.user_roles ur ON ur.user_id = u.id
            JOIN core.roles r ON r.id = ur.role_id
            LEFT JOIN attributed a ON a.creator_id = u.id
            WHERE r.code = 'central_data_entry'
              AND u.status <> 'DELETED'
            GROUP BY u.id, u.username, u.full_name, u.status
            ORDER BY projects DESC, u.full_name ASC, u.id ASC
            """, params, (rs, rowNum) -> new UserProjects(
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("status"),
                rs.getLong("projects"),
                rs.getLong("drafts"),
                rs.getLong("review"),
                rs.getLong("approved"),
                rs.getLong("archived"),
                rs.getLong("other")
        ));

        return new DataEntryProductivityResponse(
                rows.stream().mapToLong(UserProjects::totalProjects).sum(),
                rows.size(),
                rows
        );
    }

    /** View exactly the latest versions counted in a single user's summary. */
    public PagedResponse<DataEntryProjectRow> projects(UUID userId, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("رقم الصفحة أو عدد النتائج غير صالح");
        }
        var user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.getStatus() == com.centralArchiveSystem.Archive.core.user.User.UserStatus.DELETED
                || user.getRoles().stream().noneMatch(r -> "central_data_entry".equals(r.getCode()))) {
            throw new ResourceNotFoundException("Data entry user", "id", userId);
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("maxLevel", access.currentMaxLevelOrder())
                .addValue("userId", userId)
                .addValue("limit", size)
                .addValue("offset", (long) (page - 1) * size);

        String where = VISIBLE_LATEST + " AND original.created_by = :userId";
        Long total = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT latest.project_uid) " + where,
                params, Long.class
        );
        List<DataEntryProjectRow> items = jdbc.query("""
            SELECT latest.id, latest.name, latest.project_number, latest.year,
                   latest.status, latest.version_no,
                   original.created_at AS original_created_at,
                   latest.created_at AS latest_created_at
            """ + where + """
            ORDER BY original.created_at DESC, latest.id DESC
            LIMIT :limit OFFSET :offset
            """, params, (rs, rowNum) -> new DataEntryProjectRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("project_number"),
                rs.getInt("year"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getTimestamp("original_created_at").toLocalDateTime(),
                rs.getTimestamp("latest_created_at").toLocalDateTime()
        ));
        long count = total == null ? 0 : total;
        return new PagedResponse<>(items, page, size, count,
                (int) ((count + size - 1) / size));
    }
}
