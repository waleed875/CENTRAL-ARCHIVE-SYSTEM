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
        seedRole("ADMIN", "Administrator", "Full system access");
        seedRole("IMPORT_OFFICER", "Import Officer", "Can import/export packages and create projects");
        seedRole("REVIEWER", "Reviewer", "Can review, approve and correct projects");
        seedRole("USER", "User", "Standard access");
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

    private void seedAdminUser() {
        if (!bootstrapAdminEnabled || userRepository.existsByUsername(bootstrapAdminUsername)) {
            return;
        }
        User admin = new User();
        admin.setUsername(bootstrapAdminUsername);
        admin.setPasswordHash(passwordEncoder.encode(bootstrapAdminPassword));
        admin.setFullName("System Administrator");
        admin.setEmail(bootstrapAdminEmail);
        admin.setStatus(User.UserStatus.ACTIVE);

        Set<Role> roles = new HashSet<>();
        roleRepository.findByCode("ADMIN").ifPresent(roles::add);
        admin.setRoles(roles);

        userRepository.save(admin);
        log.info("Seeded bootstrap admin user (username={})", bootstrapAdminUsername);
    }
}
