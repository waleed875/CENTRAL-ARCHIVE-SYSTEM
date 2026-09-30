package com.centralArchiveSystem.Archive.import_.migration;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The kind of database a migration source reads from.
 *
 * <p>Both members are PostgreSQL connections today, and they stay separate because the runner
 * treats them differently: {@link #LEGACY_DB} is the restored staging copy of the retired archive
 * system, {@link #POSTGRESQL} is a PostgreSQL source of the same shape.
 *
 * <p>This is an enum rather than a free string because the previous check ran when a batch was
 * started, not when the source was written. A mistyped type was therefore stored happily and only
 * surfaced at run time, after the operator had already pointed a migration window at a source that
 * could not be read. Binding the value rejects it at the request instead, and the frontend gets the
 * list from {@code GET /api/v1/enums} rather than keeping a second copy of it.
 */
@Schema(description = "Type of a migration source", allowableValues = {"POSTGRESQL", "LEGACY_DB"})
public enum MigrationSourceType {
    POSTGRESQL,
    LEGACY_DB
}
