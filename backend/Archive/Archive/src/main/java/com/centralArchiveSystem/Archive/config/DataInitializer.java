package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
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

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
                           PasswordEncoder passwordEncoder) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedDefaultRoles();
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

    private void seedAdminUser() {
        if (!bootstrapAdminEnabled) {
            log.info("Bootstrap admin provisioning is disabled");
            return;
        }

        Optional<User> existingAdmin = userRepository.findByUsername(bootstrapAdminUsername);
        Role systemAdminRole = roleRepository.findByCode("system_admin").orElse(null);

        if (existingAdmin.isPresent() && systemAdminRole != null) {
            User admin = existingAdmin.get();
            admin.getRoles().add(systemAdminRole);
            if (admin.getStatus() != User.UserStatus.ACTIVE) {
                admin.setStatus(User.UserStatus.ACTIVE);
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

        Set<Role> roles = new HashSet<>();
        if (systemAdminRole != null) {
            roles.add(systemAdminRole);
        }
        admin.setRoles(roles);

        userRepository.save(admin);
        log.info("Seeded bootstrap admin user (username={})", bootstrapAdminUsername);
    }
}
