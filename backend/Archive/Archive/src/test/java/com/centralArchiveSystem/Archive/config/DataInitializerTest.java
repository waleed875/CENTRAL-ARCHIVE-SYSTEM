package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectType;
import com.centralArchiveSystem.Archive.project.projecttype.ProjectTypeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    @Test
    void disabledBootstrapDoesNotCreateReactivateOrReassignAdmin() throws Exception {
        UserRepository users = mock(UserRepository.class);

        DataInitializer initializer = initializer(mock(RoleRepository.class), users,
                mock(PasswordEncoder.class), mock(ConfidentialityLevelRepository.class),
                emptyCategoryRepository(), mock(FileTypeRepository.class),
                mock(ProjectTypeRepository.class));
        setField(initializer, "bootstrapAdminEnabled", false);
        setField(initializer, "bootstrapAdminUsername", "admin");
        setField(initializer, "bootstrapAdminPassword", "unused");
        setField(initializer, "bootstrapAdminEmail", "admin@example.test");

        initializer.run();

        verify(users, never()).findByUsername(anyString());
        verify(users, never()).save(any());
    }

    @Test
    void givesTheBootstrappedAdminTheClearanceItsJobNeeds() throws Exception {
        UUID topSecret = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("admin")).thenReturn(Optional.empty());

        DataInitializer initializer = adminInitializer(users, topSecret);
        initializer.run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        // Without a clearance the account with the most authority is refused on ordinary work:
        // ConfidentialityAccessService.requireReadAccess throws whenever the caller's clearance is
        // unset and the record names a level, and the upload and project forms both do. It answers 403,
        // which reads as a role problem and is not one.
        assertEquals(topSecret, saved.getValue().getMaxConfidentialityLevelId());
    }

    @Test
    void topsUpAnExistingAdminWhoseClearanceWasNeverSet() throws Exception {
        UUID topSecret = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("admin")).thenReturn(Optional.of(existingAdmin(null)));

        DataInitializer initializer = adminInitializer(users, topSecret);
        initializer.run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals(topSecret, saved.getValue().getMaxConfidentialityLevelId());
    }

    @Test
    void leavesAClearanceSomebodyChoseDeliberatelyAlone() throws Exception {
        UUID topSecret = UUID.randomUUID();
        UUID chosen = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("admin")).thenReturn(Optional.of(existingAdmin(chosen)));

        DataInitializer initializer = adminInitializer(users, topSecret);
        initializer.run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        // A narrower clearance is a restriction somebody put there on purpose, and widening it because
        // the bootstrap runs again is not ours to do.
        assertEquals(chosen, saved.getValue().getMaxConfidentialityLevelId());
    }

    @Test
    void seedsTheFourConfidentialityLevelsInArabicInReportOrder() throws Exception {
        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        when(levels.findByCode(anyString())).thenReturn(Optional.empty());
        when(levels.findAll()).thenReturn(List.of());

        DataInitializer initializer = initializer(mock(RoleRepository.class),
                mock(UserRepository.class), mock(PasswordEncoder.class), levels,
                emptyCategoryRepository(), mock(FileTypeRepository.class),
                mock(ProjectTypeRepository.class));
        setField(initializer, "bootstrapAdminEnabled", false);

        initializer.run();

        ArgumentCaptor<ConfidentialityLevel> saved = ArgumentCaptor.forClass(ConfidentialityLevel.class);
        verify(levels, times(4)).save(saved.capture());

        List<String> inLadderOrder = saved.getAllValues().stream()
                .sorted(Comparator.comparingInt(ConfidentialityLevel::getLevelOrder))
                .map(ConfidentialityLevel::getName)
                .toList();
        // The four names the requirements report lists, and the 1..4 order the ERD fixes.
        assertEquals(List.of("عادي", "داخلي", "سري", "سري جدًا"), inLadderOrder);
    }

    @Test
    void leavesAnExistingLadderAloneRatherThanRenumberingIt() throws Exception {
        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        when(levels.findByCode(anyString())).thenReturn(Optional.empty());
        // An administrator has already filled all four slots with levels of their own.
        when(levels.findAll()).thenReturn(List.of(
                existingLevelAt("EXISTING-1", 1),
                existingLevelAt("EXISTING-2", 2),
                existingLevelAt("EXISTING-3", 3),
                existingLevelAt("EXISTING-4", 4)));

        DataInitializer initializer = initializer(mock(RoleRepository.class),
                mock(UserRepository.class), mock(PasswordEncoder.class), levels,
                emptyCategoryRepository(), mock(FileTypeRepository.class),
                mock(ProjectTypeRepository.class));
        setField(initializer, "bootstrapAdminEnabled", false);

        initializer.run();

        verify(levels, never()).save(any());
    }

    @Test
    void seedsTheFourteenProjectTypesOfTheLegacyDictionary() throws Exception {
        ProjectTypeRepository types = mock(ProjectTypeRepository.class);

        DataInitializer initializer = initializer(mock(RoleRepository.class),
                mock(UserRepository.class), mock(PasswordEncoder.class),
                mock(ConfidentialityLevelRepository.class),
                emptyCategoryRepository(), mock(FileTypeRepository.class), types);
        setField(initializer, "bootstrapAdminEnabled", false);

        initializer.run();

        ArgumentCaptor<ProjectType> saved = ArgumentCaptor.forClass(ProjectType.class);
        verify(types, times(14)).save(saved.capture());

        List<String> codes = saved.getAllValues().stream().map(ProjectType::getCode).sorted().toList();
        // Every label of the legacy project_type enum, in Arabic, one code each.
        assertEquals(List.of("APPLIED_RESEARCH", "CONSULTING_PROJECT", "ENGINEERING_DESIGN",
                "EXECUTIVE_PROJECT", "FEASIBILITY_STUDY", "INNOVATION_PROJECT", "LAB_EXPERIMENT",
                "PROCESS_IMPROVEMENT", "PRODUCT_DEVELOPMENT", "PROTOTYPE", "REENGINEERING",
                "SCIENTIFIC_RESEARCH", "SYSTEM_ANALYSIS", "TRAINING_PROJECT"), codes);

        assertEquals("بحث علمي", saved.getAllValues().stream()
                .filter(t -> t.getCode().equals("SCIENTIFIC_RESEARCH"))
                .findFirst().orElseThrow().getName());
    }

    private static ConfidentialityLevel existingLevelAt(String code, int order) {
        ConfidentialityLevel level = new ConfidentialityLevel();
        level.setCode(code);
        level.setName(code);
        level.setLevelOrder(order);
        return level;
    }

    /**
     * A bootstrap run with the admin provisioning switched on, a system_admin role to hand out, and a
     * confidentiality ladder whose top is {@code topLevelId}.
     */
    private static DataInitializer adminInitializer(UserRepository users, UUID topLevelId) throws Exception {
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.existsByCode(anyString())).thenReturn(false);
        Role systemAdmin = mock(Role.class);
        when(systemAdmin.getCode()).thenReturn("system_admin");
        when(roles.findByCode("system_admin")).thenReturn(Optional.of(systemAdmin));

        ConfidentialityLevelRepository levels = mock(ConfidentialityLevelRepository.class);
        when(levels.findByCode(anyString())).thenReturn(Optional.empty());
        when(levels.findAll()).thenReturn(List.of());
        ConfidentialityLevel top = mock(ConfidentialityLevel.class);
        when(top.getId()).thenReturn(topLevelId);
        when(levels.findAllByOrderByLevelOrderAsc()).thenReturn(List.of(top));

        DataInitializer initializer = initializer(roles, users, mock(PasswordEncoder.class), levels,
                emptyCategoryRepository(), mock(FileTypeRepository.class),
                mock(ProjectTypeRepository.class));
        setField(initializer, "bootstrapAdminEnabled", true);
        setField(initializer, "bootstrapAdminUsername", "admin");
        setField(initializer, "bootstrapAdminPassword", "secret");
        setField(initializer, "bootstrapAdminEmail", "admin@example.test");
        return initializer;
    }

    /** An admin already on file, holding {@code clearance} — null standing for never having had one. */
    private static User existingAdmin(UUID clearance) {
        User admin = new User();
        admin.setUsername("admin");
        admin.setFullName("System Administrator");
        admin.setEmail("admin@example.test");
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setMaxConfidentialityLevelId(clearance);
        return admin;
    }

    private static DataInitializer initializer(RoleRepository roles, UserRepository users,
                                               PasswordEncoder encoder,
                                               ConfidentialityLevelRepository levels,
                                               FileCategoryRepository categories,
                                               FileTypeRepository types,
                                               ProjectTypeRepository projectTypes) {
        return new DataInitializer(roles, users, encoder, levels, categories, types, projectTypes);
    }

    /**
     * A category repository that behaves like the real one on an empty database: every lookup
     * misses and every save hands back the row it was given, so a seeded category carries the
     * id its file types are then filed under.
     */
    private static FileCategoryRepository emptyCategoryRepository() {
        FileCategoryRepository categories = mock(FileCategoryRepository.class);
        when(categories.save(any())).thenAnswer(call -> call.getArgument(0));
        return categories;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}