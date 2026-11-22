-- Preserve every administrator quarantine decision without overwriting historical review rows.
CREATE TABLE project.quarantine_reviews (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES project.files(id) ON DELETE RESTRICT,
    reviewed_by UUID NOT NULL REFERENCES core.users(id) ON DELETE RESTRICT,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('KEEP', 'RELEASE')),
    note VARCHAR(1000) NOT NULL CHECK (length(btrim(note)) >= 12),
    reviewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_quarantine_review_file_date ON project.quarantine_reviews(file_id, reviewed_at DESC);
