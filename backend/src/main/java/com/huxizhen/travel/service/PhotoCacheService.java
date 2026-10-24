package com.huxizhen.travel.service;

import com.huxizhen.travel.config.StorageProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把图片服务商（Pexels / TheMealDB）的照片镜像到本地。
 *
 * <p>原因与原实现一致：规划器已经在服务端拿到并探测过公网图片 URL，但浏览器不一定连得上
 * 那些 CDN（本机实测 Pexels 图片域名解析到一个不接受的地址）。只镜像这两个已知域名，
 * 有大小上限，并存进已有的 {@code /static} 目录。
 *
 * <p>缓存文件名是 URL 的 sha256，所以同一张图永远命中同一个文件，也不需要索引。
 */
@Service
public class PhotoCacheService {

    private static final Logger log = LoggerFactory.getLogger(PhotoCacheService.class);

    public static final Set<String> ALLOWED_HOSTS = Set.of("images.pexels.com", "www.themealdb.com");
    public static final Map<String, String> IMAGE_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");
    public static final long MAX_BYTES = 2_000_000;
    public static final Set<String> PLACEHOLDER_TYPES = Set.of("attraction", "hotel", "restaurant");

    private static final String STATIC_PREFIX = "/static/place_images/cache";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Path photoDir;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public PhotoCacheService(StorageProperties properties) {
        this.photoDir = Paths.get(properties.projectRoot())
                .toAbsolutePath()
                .normalize()
                .resolve("data")
                .resolve("photo-cache");
    }

    public Path directory() {
        return photoDir;
    }

    /** 返回本地静态路径；失败或不允许的域名返回 {@code null}。 */
    public String cachePublicPhoto(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException error) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOSTS.contains(uri.getHost())) {
            return null;
        }
        String digest = sha256(url);
        for (String extension : IMAGE_TYPES.values()) {
            Path existing = photoDir.resolve(digest + extension);
            if (Files.isRegularFile(existing) && sizeOf(existing) > 0) {
                return STATIC_PREFIX + "/" + existing.getFileName();
            }
        }

        Path temporary = null;
        try {
            Files.createDirectories(photoDir);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            String contentType = response.headers()
                    .firstValue("content-type")
                    .orElse("")
                    .split(";", 2)[0]
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String extension = IMAGE_TYPES.get(contentType);
            if (extension == null || response.body().length == 0 || response.body().length > MAX_BYTES) {
                return null;
            }
            Path destination = photoDir.resolve(digest + extension);
            temporary = photoDir.resolve("." + digest + "." + UUID.randomUUID() + ".tmp");
            Files.write(temporary, response.body());
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return STATIC_PREFIX + "/" + destination.getFileName();
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("photo cache unavailable for {}: {}", uri.getHost(), error.toString());
            return null;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    /** 把本地静态 URL 解析回文件路径，拒绝目录穿越。 */
    public Path cachedPhotoPath(String staticUrl) {
        if (staticUrl == null || !staticUrl.startsWith(STATIC_PREFIX + "/")) {
            return null;
        }
        String name = staticUrl.substring(STATIC_PREFIX.length() + 1);
        if (name.isEmpty() || !Paths.get(name).getFileName().toString().equals(name)) {
            return null;
        }
        Path path = photoDir.resolve(name);
        return Files.isRegularFile(path) && sizeOf(path) > 0 ? path : null;
    }

    /** 分类占位图；未知分类回落 generic。 */
    public static String placeholderUrl(String category) {
        String normalized = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        if (!PLACEHOLDER_TYPES.contains(normalized)) {
            normalized = "generic";
        }
        return "/static/place_images/" + normalized + ".svg";
    }

    /** 只允许镜像这两个域名的 https 图片；其余返回 {@code null}。 */
    public static String proxyPhotoUrl(String url, String category) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOSTS.contains(uri.getHost())) {
                return null;
            }
            String normalized = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
            if (!PLACEHOLDER_TYPES.contains(normalized)) {
                normalized = "generic";
            }
            return "/api/v1/image-proxy?url="
                    + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8)
                    + "&category=" + normalized;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException error) {
            return 0;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
