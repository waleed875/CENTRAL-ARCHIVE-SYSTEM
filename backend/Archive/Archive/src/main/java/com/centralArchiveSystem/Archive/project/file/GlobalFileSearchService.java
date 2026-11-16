package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.project.file.dto.GlobalFileSearchResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/** Filename-only archive search. Never searches project names or file contents. */
@Service
@Transactional(readOnly = true)
public class GlobalFileSearchService {
    private final FileRepository files;
    private final ConfidentialityAccessService clearance;

    public GlobalFileSearchService(FileRepository files, ConfidentialityAccessService clearance) {
        this.files = files;
        this.clearance = clearance;
    }

    /**
     * Escape both SQL LIKE wildcards and the escape character itself. Treat a
     * user-provided percent or underscore as literal filename text.
     */
    static String escapeLike(String input) {
        return input.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    static String normalized(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > maxLength) {
            throw new IllegalArgumentException(label + " أطول من الحد المسموح (" + maxLength + ")");
        }
        return result;
    }

    public Page<GlobalFileSearchResult> search(
            String fileName, String extension, UUID categoryId, UUID organizationId,
            String projectNumber, Integer projectYear, LocalDate from, LocalDate to,
            boolean includeVersions, String sort, int page, int size) {
        if (page < 1) throw new IllegalArgumentException("رقم الصفحة يجب أن يكون واحدًا أو أكبر");
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("تاريخ النهاية يجب ألا يسبق تاريخ البداية");
        }
        if (projectYear != null && (projectYear < 1900 || projectYear > 2100)) {
            throw new IllegalArgumentException("سنة المشروع غير صالحة");
        }
        String name = normalized(fileName, 150, "اسم الملف");
        String ext = normalized(extension, 12, "امتداد الملف");
        if (ext != null) {
            ext = ext.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            if (!ext.matches("[a-z0-9]{1,12}"))
                throw new IllegalArgumentException("امتداد الملف غير صالح");
        }
        String projectNo = normalized(projectNumber, 50, "رقم المشروع");
        String sortMode = sort == null || sort.isBlank() ? "newest" : sort;
        if (!sortMode.equals("newest") && !sortMode.equals("oldest") && !sortMode.equals("name")) {
            throw new IllegalArgumentException("ترتيب البحث غير مدعوم");
        }
        Integer maxLevel = clearance.currentMaxLevelOrder();
        return files.searchGlobalFileNames(
                name == null ? null : "%" + escapeLike(name.toLowerCase(Locale.ROOT)) + "%",
                ext, ext == null ? null : "%." + ext,
                categoryId, organizationId,
                projectNo == null ? null : "%" + escapeLike(projectNo.toLowerCase(Locale.ROOT)) + "%",
                projectYear, from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                includeVersions, maxLevel, sortMode,
                PageRequest.of(page - 1, Math.max(1, Math.min(100, size))));
    }
}
