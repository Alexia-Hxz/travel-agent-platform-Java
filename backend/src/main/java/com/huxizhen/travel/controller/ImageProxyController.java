package com.huxizhen.travel.controller;

import com.huxizhen.travel.service.PhotoCacheService;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开、同源的图片代理（前端 {@code <img src>} 带不了 Authorization 头，所以这条不鉴权）。
 *
 * <p>语义：命中缓存就返回图片字节；否则 {@code 307} 跳到分类占位图——绝不让页面挂住，
 * 也绝不把 CDN 的直接地址泄露给浏览器。
 */
@RestController
public class ImageProxyController {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final PhotoCacheService photoCacheService;

    public ImageProxyController(PhotoCacheService photoCacheService) {
        this.photoCacheService = photoCacheService;
    }

    @GetMapping("/api/v1/image-proxy")
    public ResponseEntity<Resource> proxy(
            @RequestParam("url") @Size(min = 1, max = 2048) String url,
            @RequestParam(value = "category", required = false) @Size(max = 40) String category) {
        String localUrl = photoCacheService.cachePublicPhoto(url);
        Path localPath = localUrl == null ? null : photoCacheService.cachedPhotoPath(localUrl);
        if (localPath != null) {
            return ResponseEntity.ok()
                    .header("Cache-Control", IMMUTABLE)
                    .contentType(mediaTypeOf(localPath))
                    .body(new FileSystemResource(localPath));
        }
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(PhotoCacheService.placeholderUrl(category)))
                .build();
    }

    private static MediaType mediaTypeOf(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                return MediaType.parseMediaType(probed);
            }
        } catch (IOException | RuntimeException ignored) {
            // 回落到按扩展名判断
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
