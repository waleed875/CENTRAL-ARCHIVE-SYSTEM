package com.centralArchiveSystem.Archive.common.exception;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a database or JSON failure into an Arabic message that names the field at fault.
 *
 * <p>Two failures used to reach the client as one fixed English sentence with no field in it, even
 * though the information needed to name the field was sitting in the exception:
 * <ul>
 *   <li>a constraint violation. PostgreSQL names both the constraint and the column — {@code DETAIL:
 *       Key (code)=(ORG-1) already exists.} — and that name was being discarded.</li>
 *   <li>an unreadable request body. Jackson carries the JSON path of the property it could not read,
 *       and that path was discarded too.</li>
 * </ul>
 *
 * <p>The constraint names below are the ones the schema actually creates, read out of
 * {@code pg_constraint} rather than guessed. An unrecognised name yields {@code null} so the caller
 * can fall back rather than claim a field it cannot vouch for.
 */
public final class FieldErrorMessages {

    private FieldErrorMessages() {
    }

    /**
     * Arabic label per field. Keys cover the three spellings one field can arrive in: the record
     * component (camelCase), the database column (snake_case), and the JSON property, which for
     * members is snake_case.
     */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("parentId", "المؤسسة الأم"),
            Map.entry("parent_id", "المؤسسة الأم"),
            Map.entry("name", "الاسم"),
            Map.entry("code", "الرمز"),
            Map.entry("type", "النوع"),
            Map.entry("description", "الوصف"),
            Map.entry("isActive", "الحالة"),
            Map.entry("is_active", "الحالة"),
            Map.entry("username", "اسم المستخدم"),
            Map.entry("fullName", "الاسم الكامل"),
            Map.entry("full_name", "الاسم الكامل"),
            Map.entry("password", "كلمة المرور"),
            Map.entry("reason", "سبب حذف الحساب"),
            Map.entry("deletionReason", "سبب حذف الحساب"),
            Map.entry("deletion_reason", "سبب حذف الحساب"),
            Map.entry("email", "البريد الإلكتروني"),
            Map.entry("organizationId", "المؤسسة"),
            Map.entry("organization_id", "المؤسسة"),
            Map.entry("organizationName", "اسم المؤسسة"),
            Map.entry("organization_name", "اسم المؤسسة"),
            Map.entry("maxConfidentialityLevelId", "أعلى مستوى سرية"),
            Map.entry("max_confidentiality_level_id", "أعلى مستوى سرية"),
            Map.entry("projectId", "المشروع"),
            Map.entry("projectNumber", "رقم المشروع"),
            Map.entry("project_number", "رقم المشروع"),
            Map.entry("projectCode", "رمز المشروع"),
            Map.entry("project_code", "رمز المشروع"),
            Map.entry("projectName", "اسم المشروع"),
            Map.entry("project_name", "اسم المشروع"),
            Map.entry("projectTypeId", "نوع المشروع"),
            Map.entry("project_type_id", "نوع المشروع"),
            Map.entry("confidentialityLevelId", "مستوى السرية"),
            Map.entry("confidentiality_level_id", "مستوى السرية"),
            Map.entry("year", "السنة"),
            Map.entry("executingEntity", "الجهة المنفذة"),
            Map.entry("executing_entity", "الجهة المنفذة"),
            Map.entry("supervisorName", "اسم المشرف"),
            Map.entry("supervisor_name", "اسم المشرف"),
            Map.entry("personName", "اسم عضو المشروع"),
            Map.entry("person_name", "اسم عضو المشروع"),
            Map.entry("roleName", "الدور"),
            Map.entry("role_name", "الدور"),
            Map.entry("roleIds", "الأدوار"),
            Map.entry("role_ids", "الأدوار"),
            Map.entry("status", "الحالة"),
            Map.entry("sourceType", "مصدر المشروع"),
            Map.entry("source_type", "مصدر المشروع"),
            Map.entry("levelOrder", "ترتيب المستوى"),
            Map.entry("level_order", "ترتيب المستوى"),
            Map.entry("categoryId", "فئة الملف"),
            Map.entry("category_id", "فئة الملف"),
            Map.entry("typeId", "نوع الملف"),
            Map.entry("type_id", "نوع الملف"),
            Map.entry("versionNo", "رقم النسخة"),
            Map.entry("version_no", "رقم النسخة"),
            Map.entry("projectUid", "معرّف المشروع"),
            Map.entry("project_uid", "معرّف المشروع"));

    /**
     * Constraint name to the field it is about, for the constraints a person can trigger by typing
     * data. Constraints the server sets on its own (created_by, project_uid) are deliberately absent:
     * naming a field the caller never filled in would be a worse answer than saying nothing.
     */
    private static final Map<String, String> CONSTRAINT_FIELDS = Map.ofEntries(
            // unique
            Map.entry("organizations_code_key", "code"),
            Map.entry("users_username_key", "username"),
            Map.entry("users_email_key", "email"),
            Map.entry("projects_organization_id_project_number_year_version_no_key", "projectNumber"),
            // foreign keys
            Map.entry("organizations_parent_id_fkey", "parentId"),
            Map.entry("users_organization_id_fkey", "organizationId"),
            Map.entry("users_max_confidentiality_level_id_fkey", "maxConfidentialityLevelId"),
            Map.entry("projects_organization_id_fkey", "organizationId"),
            Map.entry("projects_project_type_id_fkey", "projectTypeId"),
            Map.entry("projects_confidentiality_level_id_fkey", "confidentialityLevelId"),
            // check constraints, i.e. an enum value that is not one of the allowed ones
            Map.entry("organizations_type_check", "type"),
            Map.entry("users_status_check", "status"),
            Map.entry("chk_projects_status", "status"),
            Map.entry("projects_source_type_check", "sourceType"),
            Map.entry("projects_version_no_check", "versionNo"),
            Map.entry("projects_year_check", "year"));

    /**
     * Constraint name to the Arabic label for the same field. This exists separately from
     * {@link #LABELS} because a field name is not enough on its own: {@code code} is "الرمز" for a
     * project type and "رمز المؤسسة" for an organisation, and a message that said only "الرمز" would
     * leave the reader working out which of their records was meant. The constraint name is what
     * says which table is involved, so it is what disambiguates the label.
     */
    private static final Map<String, String> CONSTRAINT_LABELS = Map.ofEntries(
            Map.entry("organizations_code_key", "رمز المؤسسة"),
            Map.entry("users_username_key", "اسم المستخدم"),
            Map.entry("users_email_key", "البريد الإلكتروني"),
            Map.entry("projects_organization_id_project_number_year_version_no_key", "رقم المشروع"),
            Map.entry("organizations_parent_id_fkey", "المؤسسة الأم"),
            Map.entry("users_organization_id_fkey", "المؤسسة"),
            Map.entry("users_max_confidentiality_level_id_fkey", "أعلى مستوى سرية"),
            Map.entry("projects_organization_id_fkey", "المؤسسة"),
            Map.entry("projects_project_type_id_fkey", "نوع المشروع"),
            Map.entry("projects_confidentiality_level_id_fkey", "مستوى السرية"),
            Map.entry("organizations_type_check", "نوع المؤسسة"),
            Map.entry("users_status_check", "حالة المستخدم"),
            Map.entry("chk_projects_status", "حالة المشروع"),
            Map.entry("projects_source_type_check", "مصدر المشروع"),
            Map.entry("projects_version_no_check", "رقم النسخة"),
            Map.entry("projects_year_check", "سنة المشروع"));

    /**
     * The Arabic noun for each entity, used when the field alone is too generic to be useful. A
     * lookup that failed is almost always on {@code id}, and "المعرّف غير موجود" would not tell the
     * reader which record went missing.
     */
    private static final Map<String, String> RESOURCE_LABELS = Map.ofEntries(
            Map.entry("User", "المستخدم"),
            Map.entry("UserRole", "دور المستخدم"),
            Map.entry("Role", "الدور"),
            Map.entry("Organization", "المؤسسة"),
            Map.entry("ConfidentialityLevel", "مستوى السرية"),
            Map.entry("ProjectType", "نوع المشروع"),
            Map.entry("ProjectMember", "عضو المشروع"),
            Map.entry("Project", "المشروع"),
            Map.entry("File", "الملف"),
            Map.entry("FileCategory", "فئة الملف"),
            Map.entry("FileType", "نوع الملف"),
            Map.entry("BranchPackage", "حزمة الفرع"),
            Map.entry("ProjectExport", "تصدير المشاريع"),
            Map.entry("ImportOperation", "عملية الاستيراد"),
            Map.entry("MigrationSource", "مصدر الترحيل"),
            Map.entry("MigrationBatch", "دفعة الترحيل"));

    /**
     * Field labels that need the entity to be unambiguous. {@code code} alone is "الرمز", which on its
     * own leaves the reader working out whether their user, their role or their project type is at
     * fault, so the entity is folded into the label where the two readings are both plausible.
     */
    private static final Map<String, String> QUALIFIED_LABELS = Map.ofEntries(
            Map.entry("User|username", "اسم المستخدم"),
            Map.entry("User|fullName", "الاسم الكامل"),
            Map.entry("User|email", "البريد الإلكتروني"),
            Map.entry("User|code", "رمز المستخدم"),
            Map.entry("UserRole|role_id", "معرّف الدور"),
            Map.entry("Organization|code", "رمز المؤسسة"),
            Map.entry("Organization|name", "اسم المؤسسة"),
            Map.entry("Role|code", "رمز الدور"),
            Map.entry("ConfidentialityLevel|code", "رمز مستوى السرية"),
            Map.entry("ConfidentialityLevel|levelOrder", "ترتيب مستوى السرية"),
            Map.entry("ProjectType|code", "رمز نوع المشروع"),
            Map.entry("Project|code", "رمز المشروع"),
            Map.entry("Project|projectNumber", "رقم المشروع"),
            Map.entry("Project|project_code", "رمز المشروع"),
            Map.entry("Project|project_number", "رقم المشروع"),
            Map.entry("FileCategory|code", "رمز فئة الملف"),
            Map.entry("FileType|code", "رمز نوع الملف"),
            Map.entry("BranchPackage|package_uuid", "معرّف الحزمة"),
            Map.entry("BranchPackage|package_hash", "بصمة الحزمة"));

    /**
     * The allowed values per enum type, for a value rejected by {@code valueOf}. The JVM's own
     * message names the fully qualified Java class, which is both unhelpful to a person filling in a
     * form and a small disclosure of the internal package layout, so the class name is used only as
     * a lookup key and never reaches the response.
     */
    private static final Map<String, String> ENUM_VALUES = Map.ofEntries(
            Map.entry("OrganizationType", "COMPANY أو FACTORY أو LINE أو DEPARTMENT"),
            Map.entry("UserStatus", "ACTIVE أو DISABLED أو LOCKED"),
            Map.entry("DataCompletionStatus", "COMPLETE أو INCOMPLETE"),
            Map.entry("ProjectSourceType", "MANUAL أو BRANCH_PACKAGE أو MIGRATION"),
            Map.entry("ProjectStatus",
                    "DRAFT أو IMPORTED أو UNDER_REVIEW أو NEEDS_CORRECTION أو REJECTED أو APPROVED أو ARCHIVED"),
            Map.entry("MigrationSourceType", "POSTGRESQL أو LEGACY_DB"),
            Map.entry("MigrationBatchStatus", "PLANNED أو RUNNING أو SUCCESS أو FAILED أو PARTIAL"));

    /** The request field each enum lands in, so a rejected value can still name where it was sent. */
    private static final Map<String, String> ENUM_FIELDS = Map.of(
            "OrganizationType", "type",
            "UserStatus", "status",
            "DataCompletionStatus", "dataCompletionStatus",
            "ProjectSourceType", "sourceType",
            "ProjectStatus", "status",
            "MigrationSourceType", "sourceType",
            "MigrationBatchStatus", "status");

    private static final Pattern NO_ENUM_CONSTANT =
            Pattern.compile("No enum constant [\\w.$]*?(\\w+)\\.\\w+\\s*$");

    /**
     * The enum type the JVM refused to convert, e.g. {@code OrganizationType}, or {@code null} when
     * the message is not a rejected enum. Only the simple name is returned: the message carries the
     * fully qualified name and that must not leave the server.
     */
    public static String enumTypeOf(String javaMessage) {
        if (javaMessage == null) {
            return null;
        }
        Matcher matcher = NO_ENUM_CONSTANT.matcher(javaMessage.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The request field a rejected enum value was sent in, or {@code null} when the message is not a
     * rejected enum or names a type not listed above. A null is what keeps the internal class name
     * out of the response: the caller falls back to a plain Arabic sentence instead.
     */
    public static String fieldForEnum(String javaMessage) {
        String type = enumTypeOf(javaMessage);
        return type == null ? null : ENUM_FIELDS.get(type);
    }

    /**
     * The allowed values for an enum the JVM refused to convert, or {@code null} when the message is
     * not a rejected enum or names a type not listed above.
     */
    public static String enumValuesFor(String javaMessage) {
        String type = enumTypeOf(javaMessage);
        return type == null ? null : ENUM_VALUES.get(type);
    }

    /**
     * The Arabic label for a field within a known entity, falling back to the bare field label. A
     * null or unknown entity is not an error: the label degrades rather than failing, since a
     * less specific label is still better than no field named at all.
     */
    public static String label(String resource, String field) {
        if (resource == null || field == null) {
            return label(field);
        }
        String qualified = QUALIFIED_LABELS.get(resource + "|" + field);
        if (qualified != null) {
            return qualified;
        }
        if ("id".equals(field)) {
            return RESOURCE_LABELS.getOrDefault(resource, "السجل");
        }
        if ("code".equals(field) || "name".equals(field)) {
            String entity = RESOURCE_LABELS.get(resource);
            if (entity != null) {
                return ("code".equals(field) ? "رمز " : "اسم ") + entity;
            }
        }
        return label(field);
    }

    /** The entity noun for a resource name, e.g. {@code Project} to "المشروع". */
    public static String resourceLabel(String resource) {
        if (resource == null || resource.isBlank()) {
            return "السجل";
        }
        return RESOURCE_LABELS.getOrDefault(resource, resource);
    }

    /** The allowed values, for a check constraint whose whole point is a fixed set. */
    private static final Map<String, String> CHECK_ALLOWED = Map.of(
            "organizations_type_check", "COMPANY أو FACTORY أو LINE أو DEPARTMENT",
            "users_status_check", "ACTIVE أو DISABLED أو LOCKED",
            "chk_projects_status",
            "DRAFT أو IMPORTED أو UNDER_REVIEW أو NEEDS_CORRECTION أو REJECTED أو APPROVED أو ARCHIVED",
            "projects_source_type_check", "MANUAL أو BRANCH_PACKAGE أو MIGRATION");

    /**
     * Columns whose value may be echoed back to the caller. The point of quoting the value is that
     * "الرمز مستخدم من قبل" still leaves the reader hunting for which of their entries collided, so
     * the value is worth sending. It is only safe for columns that are not secrets.
     *
     * <p>Accepts either spelling, because two callers reach it with different ones: the constraint
     * path has a database column from {@code Key (code)=(...)} and the conflict path has a request
     * field from {@code ConflictException}. The four names below are spelled the same in both.
     */
    private static final java.util.Set<String> QUOTABLE = java.util.Set.of(
            "code", "username", "email", "project_number");

    private static final Pattern NAMED_CONSTRAINT = Pattern.compile("constraint \"([^\"]+)\"");
    private static final Pattern COLUMN = Pattern.compile("column \"([^\"]+)\"");
    private static final Pattern KEY_DETAIL = Pattern.compile("Key \\(([^)]+)\\)");
    private static final Pattern VALUE_DETAIL = Pattern.compile("already exists", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_VALUE = Pattern.compile(
            "Key \\(([^)]+)\\)=\\((.*)\\) already exists", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * The kind of constraint PostgreSQL refused, which decides the wording of the Arabic message. A
     * duplicate and a missing reference are both a 409, but they are different mistakes and a
     * message that does not distinguish them leaves the reader guessing which one they made.
     */
    public enum Kind {
        DUPLICATE, MISSING_REFERENCE, NOT_ALLOWED_VALUE, MISSING_VALUE, UNKNOWN
    }

    private static Kind kindOf(String sqlMessage) {
        if (sqlMessage == null) {
            return Kind.UNKNOWN;
        }
        if (sqlMessage.contains("duplicate key value")) {
            return Kind.DUPLICATE;
        }
        if (sqlMessage.contains("foreign key constraint")) {
            return Kind.MISSING_REFERENCE;
        }
        if (sqlMessage.contains("not-null constraint")) {
            return Kind.MISSING_VALUE;
        }
        if (sqlMessage.contains("check constraint")) {
            return Kind.NOT_ALLOWED_VALUE;
        }
        return Kind.UNKNOWN;
    }

    /** Which kind of constraint refused the write, so the message can say what actually went wrong. */
    public static Kind kindOfConstraint(String sqlMessage) {
        return kindOf(sqlMessage);
    }

    /**
     * The value that collided, from {@code DETAIL: Key (code)=(ORG-1) already exists}. The match is
     * greedy up to the trailing {@code ) already exists} so a value containing a parenthesis is not
     * cut short. Only call this when {@link #isQuotable(String)} allows quoting the column.
     */
    public static String findKeyValue(String sqlMessage) {
        if (sqlMessage == null) {
            return null;
        }
        Matcher matcher = KEY_VALUE.matcher(sqlMessage);
        return matcher.find() ? matcher.group(2) : null;
    }

    /**
     * The Arabic label for a field, or the field's own name when it is not one we know. A raw
     * camelCase or snake_case name in an Arabic message is still better than a sentence that names
     * nothing, so an unknown field is reported as-is rather than dropped.
     */
    public static String label(String field) {
        if (field == null || field.isBlank()) {
            return "الحقل المطلوب";
        }
        return LABELS.getOrDefault(field, field);
    }

    /**
     * The field a constraint names, or {@code null} when the constraint is not one the caller can be
     * told about. The caller uses {@code null} to decide whether it can point at a field at all.
     */
    public static String fieldForConstraint(String constraint) {
        return CONSTRAINT_FIELDS.get(constraint);
    }

    /**
     * The Arabic label for a constraint's field, which is more specific than the field name alone
     * because it knows which table was involved. Falls back to the plain field label.
     */
    public static String labelForConstraint(String constraint, String field) {
        if (constraint == null) {
            return label(field);
        }
        return CONSTRAINT_LABELS.getOrDefault(constraint, label(field));
    }

    /** The message for a check constraint, which is the allowed set rather than a field being wrong. */
    public static String allowedValuesFor(String constraint) {
        return CHECK_ALLOWED.get(constraint);
    }

    /** Whether a value in this column or field may be quoted back to the caller. */
    public static boolean isQuotable(String column) {
        return column != null && QUOTABLE.contains(column);
    }

    /**
     * The first constraint name in a PostgreSQL error, or {@code null} when the message names none.
     * PostgreSQL puts the constraint in the primary message and the offending value in DETAIL, so the
     * whole cause chain is searched rather than only the top message.
     */
    public static String findConstraint(String sqlMessage) {
        if (sqlMessage == null) {
            return null;
        }
        Matcher matcher = NAMED_CONSTRAINT.matcher(sqlMessage);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The first column name in a PostgreSQL error, or {@code null}. */
    public static String findColumn(String sqlMessage) {
        if (sqlMessage == null) {
            return null;
        }
        Matcher matcher = COLUMN.matcher(sqlMessage);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The column a unique violation collided on, taken from {@code DETAIL: Key (code)=(ORG-1)}.
     * PostgreSQL omits the {@code Key (...)} detail for every other constraint kind, so this returns
     * {@code null} for them.
     */
    public static String findKeyColumn(String sqlMessage) {
        if (sqlMessage == null) {
            return null;
        }
        Matcher matcher = KEY_DETAIL.matcher(sqlMessage);
        if (!matcher.find()) {
            return null;
        }
        String columns = matcher.group(1);
        int comma = columns.indexOf(',');
        return comma < 0 ? columns.trim() : columns.substring(0, comma).trim();
    }

    /** Whether the message is a duplicate rather than a missing reference. */
    public static boolean isDuplicate(String sqlMessage) {
        return sqlMessage != null
                && sqlMessage.contains("duplicate key value")
                && VALUE_DETAIL.matcher(sqlMessage).find();
    }

    /**
     * The Arabic label for a JSON path as Jackson reports it. A nested element arrives as
     * {@code members[0].person_name}; the index is dropped from the label because it carries no
     * meaning in Arabic, but the caller keeps the whole path as the field so a form can still point
     * at the exact row.
     */
    public static String labelForJsonPath(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) {
            return "الحقل المطلوب";
        }
        String leaf = jsonPath.substring(jsonPath.lastIndexOf('.') + 1);
        return label(leaf.replaceAll("\\[\\d+]", ""));
    }
}
