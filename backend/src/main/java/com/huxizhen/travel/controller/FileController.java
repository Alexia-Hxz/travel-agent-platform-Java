package com.huxizhen.travel.controller;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.file.TripFileRead;
import com.huxizhen.travel.dto.vision.VisionConfirmation;
import com.huxizhen.travel.dto.vision.VisionConfirmationResult;
import com.huxizhen.travel.dto.vision.VisionPreview;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.entity.TripFile;
import com.huxizhen.travel.service.FileParseService;
import com.huxizhen.travel.service.FileStorageService;
import com.huxizhen.travel.service.MultimodalService;
import com.huxizhen.travel.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传资料：列表 / 上传 / 下载 / 删除 / 解析 / 多模态预览与确认。
 *
 * <p>上传测试点：multipart 字段名必须是 {@code file}；上限 10 MiB；只收 JPG/PNG/WEBP/PDF。
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/files")
public class FileController {

    private final TripService tripService;
    private final FileStorageService fileStorageService;
    private final FileParseService fileParseService;
    private final MultimodalService multimodalService;
    private final ObjectMapper objectMapper;

    public FileController(
            TripService tripService,
            FileStorageService fileStorageService,
            FileParseService fileParseService,
            MultimodalService multimodalService,
            ObjectMapper objectMapper) {
        this.tripService = tripService;
        this.fileStorageService = fileStorageService;
        this.fileParseService = fileParseService;
        this.multimodalService = multimodalService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<TripFileRead> upload(
            @PathVariable Long tripId, @RequestParam("file") MultipartFile file) throws IOException {
        requireTrip(tripId);
        TripFile saved = fileStorageService.save(tripId, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TripFileRead.from(saved, objectMapper));
    }

    @GetMapping
    public List<TripFileRead> list(@PathVariable Long tripId) {
        requireTrip(tripId);
        return fileStorageService.list(tripId).stream()
                .map(file -> TripFileRead.from(file, objectMapper))
                .toList();
    }

    @GetMapping("/{fileId}")
    public TripFileRead get(@PathVariable Long tripId, @PathVariable Long fileId) {
        requireTrip(tripId);
        return TripFileRead.from(requireFile(tripId, fileId), objectMapper);
    }

    /** 流式下载，不把整文件读进内存。 */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long tripId, @PathVariable Long fileId) {
        requireTrip(tripId);
        TripFile tripFile = requireFile(tripId, fileId);
        Path path = fileStorageService.resolvePath(tripFile);
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("找不到已保存的文件。");
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(tripFile.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(tripFile.getContentType());
        } catch (RuntimeException error) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable Long tripId, @PathVariable Long fileId) {
        requireTrip(tripId);
        fileStorageService.delete(requireFile(tripId, fileId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{fileId}/parse")
    public TripFileRead parse(@PathVariable Long tripId, @PathVariable Long fileId) {
        requireTrip(tripId);
        TripFile parsed = fileParseService.parse(requireFile(tripId, fileId));
        return TripFileRead.from(parsed, objectMapper);
    }

    /** 状态轮询：解析是同步的，但前端仍会先看一眼状态。 */
    @GetMapping("/{fileId}/parse")
    public TripFileRead parseStatus(@PathVariable Long tripId, @PathVariable Long fileId) {
        requireTrip(tripId);
        return TripFileRead.from(requireFile(tripId, fileId), objectMapper);
    }

    @GetMapping("/{fileId}/parse/result")
    public TripFileRead parseResult(@PathVariable Long tripId, @PathVariable Long fileId) {
        requireTrip(tripId);
        TripFile tripFile = requireFile(tripId, fileId);
        if (!"completed".equals(tripFile.getParseStatus())) {
            throw ApiException.conflict("文件还没有解析完成。");
        }
        return TripFileRead.from(tripFile, objectMapper);
    }

    @PostMapping("/{fileId}/vision/preview")
    public VisionPreview preview(@PathVariable Long tripId, @PathVariable Long fileId) {
        Trip trip = requireTrip(tripId);
        return multimodalService.createPreview(trip, requireFile(tripId, fileId));
    }

    @PostMapping("/{fileId}/vision/confirm")
    public ResponseEntity<VisionConfirmationResult> confirm(
            @PathVariable Long tripId,
            @PathVariable Long fileId,
            @RequestBody VisionConfirmation payload) {
        Trip trip = requireTrip(tripId);
        requireFile(tripId, fileId);
        if (payload.saveItinerary() && !payload.savePlace()) {
            throw ApiException.unprocessable("保存行程时段前，需要先保存它对应的地点。");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(multimodalService.confirm(trip, payload));
    }

    private Trip requireTrip(Long tripId) {
        Trip trip = tripService.get(tripId);
        if (trip == null) {
            throw ApiException.notFound("旅行项目不存在。");
        }
        return trip;
    }

    private TripFile requireFile(Long tripId, Long fileId) {
        TripFile tripFile = fileStorageService.get(tripId, fileId);
        if (tripFile == null) {
            throw ApiException.notFound("文件不存在。");
        }
        return tripFile;
    }
}
