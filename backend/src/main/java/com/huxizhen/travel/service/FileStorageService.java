package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.config.StorageProperties;
import com.huxizhen.travel.entity.TripFile;
import com.huxizhen.travel.mapper.TripFileMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传资料的落盘与读取。
 *
 * <p>关键约束（与原实现逐条一致）：
 * <ul>
 *   <li>只收 JPG / JPEG / PNG / WEBP / PDF，单个 ≤ 10 MiB；</li>
 *   <li>扩展名不可信时依次回落到 Content-Type、文件头魔数（部分客户端给非 ASCII 文件名会发
 *       {@code application/octet-stream}）；</li>
 *   <li>落盘文件名是随机 UUID，原始名只存数据库；</li>
 *   <li>读取路径必须落在 {@code data/uploads} 之内（防目录穿越）；</li>
 *   <li>删除时**先删向量、再删行**（S4 接入），避免"文件已删但仍可检索"。</li>
 * </ul>
 */
@Service
public class FileStorageService {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".webp", ".pdf");

    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/jpg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "application/pdf", ".pdf");

    private final TripFileMapper tripFileMapper;
    private final VectorIndexService vectorIndexService;
    private final Path projectRoot;
    private final Path uploadsRoot;

    public FileStorageService(
            TripFileMapper tripFileMapper,
            StorageProperties properties,
            VectorIndexService vectorIndexService) {
        this.tripFileMapper = tripFileMapper;
        this.vectorIndexService = vectorIndexService;
        this.projectRoot = Paths.get(properties.projectRoot()).toAbsolutePath().normalize();
        this.uploadsRoot = projectRoot.resolve("data").resolve("uploads").normalize();
    }

    public List<TripFile> list(Long tripId) {
        return tripFileMapper.selectList(Wrappers.<TripFile>lambdaQuery()
                .eq(TripFile::getTripId, tripId)
                .orderByAsc(TripFile::getCreatedAt)
                .orderByAsc(TripFile::getId));
    }

    public TripFile get(Long tripId, Long fileId) {
        return tripFileMapper.selectOne(Wrappers.<TripFile>lambdaQuery()
                .eq(TripFile::getId, fileId)
                .eq(TripFile::getTripId, tripId));
    }

    @Transactional
    public TripFile save(Long tripId, MultipartFile upload) throws IOException {
        String originalFilename = safeFilename(upload.getOriginalFilename());
        String extension = resolveExtension(upload, originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("只支持 JPG、JPEG、PNG、WEBP 和 PDF 文件。");
        }

        Path tripDirectory = uploadsRoot.resolve(String.valueOf(tripId));
        Files.createDirectories(tripDirectory);
        String storedFilename = UUID.randomUUID().toString().replace("-", "") + extension;
        Path storedPath = tripDirectory.resolve(storedFilename);

        long fileSize = 0;
        try (InputStream input = upload.getInputStream();
                OutputStream output = Files.newOutputStream(storedPath)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                fileSize += read;
                if (fileSize > MAX_FILE_SIZE) {
                    throw ApiException.badRequest("文件大小不能超过 10 MB。");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(storedPath);
            throw error;
        }

        TripFile tripFile = new TripFile();
        tripFile.setTripId(tripId);
        tripFile.setOriginalFilename(originalFilename);
        tripFile.setStoredFilename(storedFilename);
        tripFile.setContentType(
                upload.getContentType() == null || upload.getContentType().isBlank()
                        ? "application/octet-stream"
                        : upload.getContentType());
        tripFile.setFileSize(fileSize);
        tripFile.setFilePath(relativeToProjectRoot(storedPath));
        tripFile.setParseStatus("pending");
        tripFile.setIndexStatus("pending");
        tripFile.setIndexedChunks(0);
        try {
            tripFileMapper.insert(tripFile);
        } catch (RuntimeException error) {
            Files.deleteIfExists(storedPath);
            throw error;
        }
        return tripFileMapper.selectById(tripFile.getId());
    }

    /** 把 {@code file_path} 解析成真实路径；不在 uploads 目录下的一律 404。 */
    public Path resolvePath(TripFile tripFile) {
        Path resolved = projectRoot.resolve(tripFile.getFilePath()).normalize();
        if (!resolved.startsWith(uploadsRoot)) {
            throw ApiException.notFound("找不到已保存的文件。");
        }
        return resolved;
    }

    @Transactional
    public void delete(TripFile tripFile) {
        deleteVectors(tripFile.getId());
        try {
            Files.deleteIfExists(resolvePath(tripFile));
        } catch (IOException error) {
            throw new IllegalStateException("could not delete stored file", error);
        }
        tripFileMapper.deleteById(tripFile.getId());
    }

    /** 删除一趟旅行的全部文件（删行程时调用）。 */
    public void deleteForTrip(Long tripId) {
        for (TripFile tripFile : list(tripId)) {
            delete(tripFile);
        }
        Path tripDirectory = uploadsRoot.resolve(String.valueOf(tripId));
        if (Files.isDirectory(tripDirectory)) {
            try (var entries = Files.list(tripDirectory)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(tripDirectory);
                }
            } catch (IOException ignored) {
                // 目录删不掉不影响数据正确性，下次上传还会复用它。
            }
        }
    }

    /**
     * 删除一个文件的向量片段。**先删向量、再删行**：反过来一旦删向量失败，就会留下一份
     * "文件已删但仍可检索"的资料。
     */
    protected void deleteVectors(Long fileId) {
        vectorIndexService.deleteFileChunks(fileId);
    }

    private String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String name = Paths.get(original.replace('\\', '/')).getFileName().toString();
        return name.isBlank() ? "upload" : name;
    }

    private String relativeToProjectRoot(Path path) {
        return projectRoot.relativize(path).toString().replace('\\', '/');
    }

    private String resolveExtension(MultipartFile upload, String originalFilename) throws IOException {
        String extension = extensionOf(originalFilename);
        if (ALLOWED_EXTENSIONS.contains(extension)) {
            return extension;
        }
        String contentType = upload.getContentType() == null
                ? ""
                : upload.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (CONTENT_TYPE_EXTENSIONS.containsKey(contentType)) {
            return CONTENT_TYPE_EXTENSIONS.get(contentType);
        }
        byte[] header = new byte[16];
        int read;
        try (InputStream input = upload.getInputStream()) {
            read = input.read(header);
        }
        if (read >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (read >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return ".png";
        }
        if (read >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return ".webp";
        }
        if (read >= 4 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F') {
            return ".pdf";
        }
        return extension;
    }

    public static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** 测试用：把 {@code data/uploads} 暴露出来，避免测试自己拼路径。 */
    public Path uploadsRoot() {
        return uploadsRoot;
    }

    /** 移动临时文件到目标位置（供测试与后续工具复用）。 */
    public void move(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
}
