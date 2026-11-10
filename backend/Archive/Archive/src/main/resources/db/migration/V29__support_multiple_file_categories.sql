-- Keep the existing category_id as the primary/legacy category and add an
-- assignment table for additional categories. Existing files are backfilled.
CREATE TABLE project.file_category_assignments (
    file_id UUID NOT NULL REFERENCES project.files(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES project.file_categories(id),
    CONSTRAINT pk_file_category_assignments PRIMARY KEY (file_id, category_id)
);

INSERT INTO project.file_category_assignments(file_id, category_id)
SELECT id, category_id
FROM project.files
WHERE category_id IS NOT NULL
ON CONFLICT DO NOTHING;

CREATE INDEX ix_file_category_assignments_category
    ON project.file_category_assignments(category_id);
