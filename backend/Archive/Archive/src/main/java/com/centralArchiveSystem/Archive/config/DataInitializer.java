package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileType;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectType;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ConfidentialityLevelRepository confidentialityLevelRepository;
    private final FileCategoryRepository fileCategoryRepository;
    private final FileTypeRepository fileTypeRepository;
    private final ProjectTypeRepository projectTypeRepository;

    @Value("${app.bootstrap.admin.enabled:true}")
    private boolean bootstrapAdminEnabled;

    @Value("${app.bootstrap.admin.username:admin}")
    private String bootstrapAdminUsername;

    @Value("${app.bootstrap.admin.password:admin123}")
    private String bootstrapAdminPassword;

    @Value("${app.bootstrap.admin.email:admin@central-archive.local}")
    private String bootstrapAdminEmail;

    public DataInitializer(RoleRepository roleRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           ConfidentialityLevelRepository confidentialityLevelRepository,
                           FileCategoryRepository fileCategoryRepository,
                           FileTypeRepository fileTypeRepository,
                           ProjectTypeRepository projectTypeRepository) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.confidentialityLevelRepository = confidentialityLevelRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileTypeRepository = fileTypeRepository;
        this.projectTypeRepository = projectTypeRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedDefaultRoles();
        seedConfidentialityLevels();
        seedProjectTypes();
        seedFileCategories();
        seedFileTypes();
        seedAdminUser();
    }

    private void seedDefaultRoles() {
        seedRole("system_admin", "مدير النظام", "إدارة المستخدمين والأدوار والجهات والنظام والصلاحيات الحساسة");
        seedRole("central_data_entry", "مدخل بيانات مركزي", "إنشاء المشاريع وتعديلها وإضافة الملفات من داخل المركز");
        seedRole("auditor", "مدقق", "مراجعة المشاريع والملفات ثم الاعتماد أو طلب الاستكمال/الرفض");
        seedRole("archive_officer", "مسؤول أرشفة", "تنفيذ وإدارة عمليات أرشفة المشاريع");
        seedRole("import_officer", "مسؤول استيراد", "استيراد الحزم إلى النظام المركزي");
        seedRole("export_officer", "مسؤول تصدير", "تصدير المشاريع والملفات والحزم");
        seedRole("read_only", "مستخدم قراءة فقط", "استعراض البيانات المسموح بها بدون تعديل");
        deactivateLegacyRoles();
    }

    private void seedRole(String code, String name, String description) {
        if (roleRepository.existsByCode(code)) {
            return;
        }
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setIsActive(true);
        roleRepository.save(role);
        log.info("Seeded role: {}", code);
    }

    private void deactivateLegacyRoles() {
        List<String> legacyCodes = List.of("ADMIN", "IMPORT_OFFICER", "REVIEWER", "USER");
        for (String code : legacyCodes) {
            roleRepository.findByCode(code).ifPresent(role -> {
                role.setIsActive(false);
                roleRepository.save(role);
                log.info("Deactivated legacy role: {}", code);
            });
        }
    }

    /**
     * The four confidentiality levels named in the requirements report (section 10,
     * "أمثلة مبدئية لمستويات السرية": عادي، داخلي، سري، سري جدًا) and in the ERD,
     * which fixes the comparison order at 1 to 4.
     *
     * <p>The report gives the Arabic names only. The codes are the ASCII slugs the
     * column requires; no behaviour anywhere depends on a particular code, so they
     * are free to be spelled this way.
     */
    private void seedConfidentialityLevels() {
        seedConfidentialityLevel("NORMAL", "عادي", 1);
        seedConfidentialityLevel("INTERNAL", "داخلي", 2);
        seedConfidentialityLevel("CONFIDENTIAL", "سري", 3);
        seedConfidentialityLevel("TOP_SECRET", "سري جدًا", 4);
    }

    private void seedConfidentialityLevel(String code, String name, int order) {
        if (confidentialityLevelRepository.findByCode(code).isPresent()) {
            return;
        }
        if (confidentialityLevelRepository.findAll().stream()
                .anyMatch(level -> level.getLevelOrder() == order)) {
            // level_order is UNIQUE and the report fixes the ladder at 1..4, so an
            // occupied slot means this installation orders its levels differently.
            // Renumbering an administrator's levels is not ours to do.
            log.info("Confidentiality level {} skipped: level_order {} is already taken",
                    code, order);
            return;
        }
        ConfidentialityLevel level = new ConfidentialityLevel();
        level.setCode(code);
        level.setName(name);
        level.setLevelOrder(order);
        confidentialityLevelRepository.save(level);
        log.info("Seeded confidentiality level: {} ({})", name, code);
    }

    /**
     * The eight file categories named in the requirements report, section 9.1
     * ("أمثلة على التصنيفات").
     *
     * <p>The report's other taxonomy, the table in section 9.2, groups thirteen
     * sub-types under four categories of its own (التجارب، الوثائق، البرمجيات، الوسائط).
     * Two of those four are spelled exactly like categories above and two are not, so
     * the report never states where its sub-types sit relative to this list. That is
     * why the sub-types below are attached only where the category name is either
     * identical or a one-word synonym, and why "صوت" is left out: none of the eight
     * categories was originally missing audio, so the new AUDIO category groups
     * sound recordings without changing the meaning of the existing categories.
     *
     * <p>The legacy migration creates its own LEGACY category and LEGACY-FILE type on
     * demand, so they are deliberately absent here.
     */
    private void seedFileCategories() {
        seedFileCategory("DOCUMENTS", "وثائق");
        seedFileCategory("EXPERIMENTS", "تجارب");
        seedFileCategory("IMAGES", "صور");
        seedFileCategory("VIDEOS", "فيديوهات");
        seedFileCategory("AUDIO", "تسجيلات صوتية");
        seedFileCategory("TESTS", "اختبارات");
        seedFileCategory("SOURCE_CODE", "أكواد برمجية");
        seedFileCategory("REPORTS", "تقارير");
        seedFileCategory("SUPPORTING_ATTACHMENTS", "مرفقات داعمة");
    }

    private FileCategory seedFileCategory(String code, String name) {
        return fileCategoryRepository.findByCode(code)
                .orElseGet(() -> {
                    FileCategory created = new FileCategory();
                    created.setCode(code);
                    created.setName(name);
                    created.setIsActive(true);
                    FileCategory saved = fileCategoryRepository.save(created);
                    log.info("Seeded file category: {}", name);
                    return saved;
                });
    }

    /**
     * The sub-types of section 9.2, attached to the categories seeded above.
     *
     * <p>Every name is quoted from that table. The category each one is filed under is
     * the only judgement here, and it is recorded per group below so it can be moved
     * in one edit if the report is read differently.
     */
    private void seedFileTypes() {
        // §9.2 "التجارب" -> §9.1 "تجارب"
        seedFileTypesUnder("EXPERIMENTS", "تجارب",
                new String[][]{
                        {"EXPERIMENTS-RUNS", "تجارب"},
                        {"EXPERIMENTS-VIDEO", "فيديوهات تجارب"},
                        {"EXPERIMENTS-RESULTS", "نتائج اختبار"},
                        {"EXPERIMENTS-RAW-DATA", "بيانات خام"}});
        // §9.2 "الوثائق" -> §9.1 "وثائق"
        seedFileTypesUnder("DOCUMENTS", "وثائق",
                new String[][]{
                        {"DOCUMENTS-CORRESPONDENCE", "مراسلات"},
                        {"DOCUMENTS-REPORT", "تقارير"},
                        {"DOCUMENTS-PDF", "PDF"},
                        {"DOCUMENTS-WORD", "Word"},
                        {"DOCUMENTS-EXCEL", "Excel"}});
        // §9.2 "البرمجيات" -> §9.1 "أكواد برمجية"
        seedFileTypesUnder("SOURCE_CODE", "أكواد برمجية",
                new String[][]{
                        {"SOURCE-CODE", "كود مصدري"},
                        {"SOURCE-CONFIG", "ملفات إعدادات"},
                        {"SOURCE-BINARY", "ملفات تنفيذية"},
                        {"SOURCE-TECHNICAL-DOC", "توثيق تقني"}});
        // Preserve the legacy generic media types. Add explicit extension-bound types
        // for supported safe media and archives; executable binaries remain blocked.
        seedFileTypesUnder("IMAGES", "صور", new String[][]{
                {"MEDIA-IMAGE", "صور"},
                {"MEDIA-HEIC", "صور HEIC", "heic"},
                {"MEDIA-AVIF", "صور AVIF", "avif"}});
        seedFileTypesUnder("VIDEOS", "فيديوهات", new String[][]{
                {"MEDIA-VIDEO", "فيديو"},
                {"MEDIA-WEBM", "فيديو WebM", "webm"},
                {"MEDIA-MKV", "فيديو Matroska", "mkv"},
                {"MEDIA-AVI", "فيديو AVI", "avi"}});
        seedFileTypesUnder("AUDIO", "تسجيلات صوتية", new String[][]{
                {"AUDIO-MP3", "صوت MP3", "mp3"},
                {"AUDIO-WAV", "صوت WAV", "wav"},
                {"AUDIO-FLAC", "صوت FLAC", "flac"},
                {"AUDIO-M4A", "صوت M4A", "m4a"}});
        seedFileTypesUnder("SUPPORTING_ATTACHMENTS", "مرفقات داعمة", new String[][]{
                {"ARCHIVE-7Z", "أرشيف 7Z", "7z"},
                {"ARCHIVE-TAR", "أرشيف TAR", "tar"},
                {"ARCHIVE-GZ", "ملف GZip", "gz"}});
    }

    private void seedFileTypesUnder(String categoryCode, String categoryName, String[][] types) {
        FileCategory category = seedFileCategory(categoryCode, categoryName);
        for (String[] type : types) {
            if (fileTypeRepository.findByCode(type[0]).isPresent()) {
                continue;
            }
            FileType fileType = new FileType();
            fileType.setCategoryId(category.getId());
            fileType.setCode(type[0]);
            fileType.setName(type[1]);
            if (type.length > 2) {
                fileType.setDefaultExtension(type[2]);
            }
            fileType.setIsActive(true);
            fileTypeRepository.save(fileType);
        }
        log.info("Seeded {} file type(s) under category {}", types.length, categoryName);
    }

    /**
     * The fourteen project types of the legacy system, taken from the {@code project_type}
     * enum in the legacy database (Archive2.public.project_type). They are fixed there,
     * which is why the central platform treats them as a dictionary to distribute rather
     * than something a terminal invents: the ERD calls project_types "قاموس أنواع
     * المشاريع في المركز. يوزع للطرفية كقائمة جاهزة للمرحلة الأولى", and the migration
     * needs them because it resolves each legacy project_type_id through an explicit
     * mapping instead of creating anything.
     *
     * <p>The legacy labels separate words with an underscore because an enum label cannot
     * hold a space comfortably; the names below keep the same words with real spaces,
     * because they are shown in pickers. The codes are ASCII slugs, as the column
     * requires, and nothing in the codebase resolves a project type by code.
     */
    private void seedProjectTypes() {
        seedProjectType("SCIENTIFIC_RESEARCH", "بحث علمي");
        seedProjectType("APPLIED_RESEARCH", "بحث تطبيقي");
        seedProjectType("PRODUCT_DEVELOPMENT", "تطوير منتج");
        seedProjectType("ENGINEERING_DESIGN", "تصميم هندسي");
        seedProjectType("PROTOTYPE", "نموذج أولي");
        seedProjectType("LAB_EXPERIMENT", "تجربة مخبرية");
        seedProjectType("FEASIBILITY_STUDY", "دراسة جدوى");
        seedProjectType("SYSTEM_ANALYSIS", "تحليل نظام");
        seedProjectType("PROCESS_IMPROVEMENT", "تحسين عملية");
        seedProjectType("REENGINEERING", "إعادة هندسة");
        seedProjectType("EXECUTIVE_PROJECT", "مشروع تنفيذي");
        seedProjectType("TRAINING_PROJECT", "مشروع تدريبي");
        seedProjectType("CONSULTING_PROJECT", "مشروع استشاري");
        seedProjectType("INNOVATION_PROJECT", "مشروع ابتكار");
    }

    private void seedProjectType(String code, String name) {
        if (projectTypeRepository.existsByCode(code)) {
            return;
        }
        ProjectType projectType = new ProjectType();
        projectType.setCode(code);
        projectType.setName(name);
        projectType.setIsActive(true);
        projectTypeRepository.save(projectType);
        log.info("Seeded project type: {}", name);
    }

    private void seedAdminUser() {
        if (!bootstrapAdminEnabled) {
            log.info("Bootstrap admin provisioning is disabled");
            return;
        }

        Optional<User> existingAdmin = userRepository.findByUsername(bootstrapAdminUsername);
        Role systemAdminRole = roleRepository.findByCode("system_admin").orElse(null);
        // The system administrator is the one account that has to reach every record, so it is given the
        // top clearance. Left without one it is the most locked-out account in the system rather than the
        // least: `ConfidentialityAccessService.requireReadAccess` refuses any record naming a
        // confidentiality level whenever the caller's clearance is unset, and that is every project and
        // file filled in properly — the answer is a bare 403 that names a role problem and is not one.
        UUID fullClearance = highestConfidentialityLevelId();

        if (existingAdmin.isPresent() && systemAdminRole != null) {
            User admin = existingAdmin.get();
            admin.getRoles().add(systemAdminRole);
            if (admin.getStatus() != User.UserStatus.ACTIVE) {
                admin.setStatus(User.UserStatus.ACTIVE);
            }
            if (admin.getMaxConfidentialityLevelId() == null && fullClearance != null) {
                admin.setMaxConfidentialityLevelId(fullClearance);
            }
            userRepository.save(admin);
            log.info("Assigning system_admin role to existing bootstrap admin user (username={})", bootstrapAdminUsername);
            return;
        }

        if (existingAdmin.isPresent()) {
            return;
        }

        User admin = new User();
        admin.setUsername(bootstrapAdminUsername);
        admin.setPasswordHash(passwordEncoder.encode(bootstrapAdminPassword));
        admin.setFullName("System Administrator");
        admin.setEmail(bootstrapAdminEmail);
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setMaxConfidentialityLevelId(fullClearance);

        Set<Role> roles = new HashSet<>();
        if (systemAdminRole != null) {
            roles.add(systemAdminRole);
        }
        admin.setRoles(roles);

        userRepository.save(admin);
        log.info("Seeded bootstrap admin user (username={})", bootstrapAdminUsername);
    }

    /**
     * The strictest level on the ladder, which is the clearance that lets its holder read everything.
     *
     * <p>Null before the ladder is seeded, and left null rather than guessed at: a clearance has to name a
     * level that exists, and inventing one would fail the foreign key on save.
     */
    private UUID highestConfidentialityLevelId() {
        List<ConfidentialityLevel> ladder = confidentialityLevelRepository.findAllByOrderByLevelOrderAsc();
        return ladder.isEmpty() ? null : ladder.get(ladder.size() - 1).getId();
    }
}
