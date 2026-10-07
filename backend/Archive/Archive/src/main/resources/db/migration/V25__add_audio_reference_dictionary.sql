-- Add the missing audio dictionary without moving or renaming existing categories.
-- Respect administrator-created definitions with these stable codes.
INSERT INTO project.file_categories (code, name, is_active)
VALUES ('AUDIO', 'ملفات صوتية', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO project.file_types (category_id, code, name, is_active)
SELECT id, 'MEDIA-AUDIO', 'صوت', TRUE
FROM project.file_categories WHERE code = 'AUDIO'
ON CONFLICT (code) DO NOTHING;
