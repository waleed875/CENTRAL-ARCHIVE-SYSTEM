package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import com.centralArchiveSystem.Archive.project.file.FileContentInspector;
import com.centralArchiveSystem.Archive.project.file.FilePreviewService;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads a single verified file directly from a package without creating projects
 * or file records. Indexed paths, bounded decompression, and SHA-256 prevent
 * arbitrary MinIO access and serving tampered package entries.
 */
@Service
public class PackageFilePreviewService {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final int MAX_OFFICE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private final BranchPackageRepository packages;
    private final MinioService minio;
    private final MinioConfig minioConfig;
    private final PackageArchiveService archives;
    private final ObjectMapper mapper;

    public record PreviewFile(String originalName, String extension,
                              String mimeType, byte[] bytes) {}

    public PackageFilePreviewService(BranchPackageRepository packages, MinioService minio,
                                     MinioConfig minioConfig, PackageArchiveService archives,
                                     ObjectMapper mapper) {
        this.packages = packages;
        this.minio = minio;
        this.minioConfig = minioConfig;
        this.archives = archives;
        this.mapper = mapper;
    }

    public PreviewFile read(UUID packageId, int projectIndex, int fileIndex) {
        BranchPackage pkg = packages.findById(packageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "الحزمة المطلوبة غير موجودة"));
        if (pkg.getStatus() != BranchPackage.PackageStatus.VERIFIED
                && pkg.getStatus() != BranchPackage.PackageStatus.IMPORTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "يجب التحقق من الحزمة أولًا قبل فتح ملفاتها");
        }
        if (pkg.getManifestData() == null || pkg.getManifestHash() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "لا يتوفر فهرس حزمة تم التحقق منه");
        }
        try {
            JsonNode manifest = mapper.readTree(pkg.getManifestData());
            JsonNode projects = manifest.path("projects");
            if (!projects.isArray() || projectIndex < 0 || projectIndex >= projects.size()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "المشروع غير موجود في الحزمة");
            }
            JsonNode entries = projects.get(projectIndex).path("files");
            if (!entries.isArray() || fileIndex < 0 || fileIndex >= entries.size()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "الملف غير موجود في الحزمة");
            }
            JsonNode file = entries.get(fileIndex);
            String filename = file.path("original_name").asText(file.path("name").asText(""));
            if (filename.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "اسم ملف الحزمة مفقود");
            }
            String extension = file.path("extension").asText("");
            if (extension.isBlank()) extension = FileContentInspector.extensionFromName(filename);
            if (extension == null) extension = "";
            extension = extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            String mime;
            try {
                mime = FileContentInspector.mimeTypeForExtension(extension);
            } catch (IllegalArgumentException unsupported) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "هذا النوع غير مدعوم للمعاينة");
            }
            if (FilePreviewService.classify(filename, mime) == FilePreviewService.Kind.UNSUPPORTED) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "هذا النوع غير مدعوم للمعاينة داخل الموقع");
            }
            long size = file.path("size_bytes").asLong(-1);
            String checksum = file.path("checksum_sha256").asText("");
            if (size < 0 || !checksum.matches("[0-9a-fA-F]{64}")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "بيانات سلامة الملف في الحزمة غير مكتملة");
            }
            int limit = switch (FilePreviewService.classify(filename, mime)) {
                case OFFICE -> MAX_OFFICE_BYTES;
                case TEXT -> MAX_TEXT_BYTES;
                default -> MAX_BYTES;
            };
            if (size > limit) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "حجم الملف أكبر من الحد الآمن لمعاينته داخل الحزمة");
            }
            String archiveKey = "packages/" + pkg.getPackageUuid()
                    + (Boolean.TRUE.equals(pkg.getEncrypted()) ? "/import-ready.pkg" : "/original.pkg");
            String entry = archives.resolveFileEntryPath(file);
            final int readLimit = limit;
            final byte[][] found = new byte[1][];
            try (InputStream source = minio.downloadFile(minioConfig.getBucket(), archiveKey)) {
                archives.withEntry(source, entry, (stream, declaredSize) -> {
                    byte[] content = stream.readNBytes(readLimit + 1);
                    if (content.length > readLimit) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "ملف الحزمة أكبر من الحد الآمن للمعاينة");
                    }
                    found[0] = content;
                });
            }
            if (found[0] == null || found[0].length != size
                    || !archives.sha256(found[0]).equalsIgnoreCase(checksum)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "محتوى الملف لا يطابق البصمة أو الحجم المسجل في الحزمة");
            }
            return new PreviewFile(filename, extension, mime, found[0]);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر فتح الملف من الحزمة بعد التحقق من سلامته", e);
        }
    }
}
