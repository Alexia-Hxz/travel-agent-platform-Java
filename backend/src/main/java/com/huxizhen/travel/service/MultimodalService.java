package com.huxizhen.travel.service;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.itinerary.ItineraryItemCreate;
import com.huxizhen.travel.dto.itinerary.ItineraryItemRead;
import com.huxizhen.travel.dto.place.PlaceCreate;
import com.huxizhen.travel.dto.place.PlaceRead;
import com.huxizhen.travel.dto.place.PlaceType;
import com.huxizhen.travel.dto.vision.VisionConfirmation;
import com.huxizhen.travel.dto.vision.VisionConfirmationResult;
import com.huxizhen.travel.dto.vision.VisionItinerarySuggestion;
import com.huxizhen.travel.dto.vision.VisionPlaceSuggestion;
import com.huxizhen.travel.dto.vision.VisionPreview;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.entity.TripFile;
import com.huxizhen.travel.integration.vision.VisionService;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多模态链路：预览 → 用户确认 → 入库。
 *
 * <p>两个阶段分开是刻意的：模型识别出的东西只是**建议**，用户逐项确认后才写进收藏与行程。
 * 确认阶段一旦出错必须整体回滚（地点建了一半、行程没建成，比什么都没建更糟）。
 */
@Service
public class MultimodalService {

    private final VisionService visionService;
    private final FileStorageService fileStorageService;
    private final PlaceService placeService;
    private final ItineraryService itineraryService;

    public MultimodalService(
            VisionService visionService,
            FileStorageService fileStorageService,
            PlaceService placeService,
            ItineraryService itineraryService) {
        this.visionService = visionService;
        this.fileStorageService = fileStorageService;
        this.placeService = placeService;
        this.itineraryService = itineraryService;
    }

    public VisionPreview createPreview(Trip trip, TripFile tripFile) {
        try {
            String contentType = tripFile.getContentType();
            if ("application/octet-stream".equals(contentType)) {
                contentType = VisionService.normalizeContentType(
                        contentType, java.nio.file.Path.of(tripFile.getFilePath()));
            }
            VisionPreview preview = visionService.analyze(fileStorageService.resolvePath(tripFile), contentType);
            VisionItinerarySuggestion itinerary = preview.itinerary();
            if (itinerary != null
                    && (itinerary.date().isBefore(trip.getStartDate())
                            || itinerary.date().isAfter(trip.getEndDate()))) {
                return new VisionPreview(
                        preview.documentType(),
                        preview.summary() + " 订单日期不在当前旅行日期内，未生成每日行程建议。",
                        preview.place(),
                        null,
                        preview.referenceNumber(),
                        preview.rawText());
            }
            return preview;
        } catch (VisionService.VisionConfigurationException error) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "多模态解析还没有配置（缺少 DASHSCOPE_API_KEY）。");
        } catch (VisionService.VisionProviderException error) {
            throw ApiException.unprocessable(error.getMessage());
        }
    }

    @Transactional
    public VisionConfirmationResult confirm(Trip trip, VisionConfirmation confirmation) {
        if (!confirmation.savePlace() && !confirmation.saveItinerary()) {
            return new VisionConfirmationResult(null, null);
        }
        if (confirmation.savePlace() && confirmation.place() == null) {
            throw ApiException.unprocessable("需要先选择要保存的地点。");
        }
        if (confirmation.saveItinerary() && confirmation.itinerary() == null) {
            throw ApiException.unprocessable("需要先选择要保存的行程时段。");
        }
        if (confirmation.saveItinerary()) {
            var date = confirmation.itinerary().date();
            if (date.isBefore(trip.getStartDate()) || date.isAfter(trip.getEndDate())) {
                throw ApiException.unprocessable("行程日期不在当前旅行日期范围内。");
            }
        }

        PlaceRead place = null;
        ItineraryItemRead itinerary = null;
        if (confirmation.savePlace()) {
            place = placeService.create(trip.getId(), toPlaceCreate(confirmation.place()));
        }
        if (confirmation.saveItinerary()) {
            VisionItinerarySuggestion suggestion = confirmation.itinerary();
            ItineraryItemRead item = itineraryService.create(
                    trip,
                    new ItineraryItemCreate(
                            suggestion.date(),
                            LocalTime.parse(suggestion.startTime()),
                            LocalTime.parse(suggestion.endTime()),
                            place.id(),
                            suggestion.note(),
                            0));
            if (item == null) {
                throw ApiException.unprocessable("行程日期不在当前旅行日期范围内。");
            }
            itinerary = item;
        }
        return new VisionConfirmationResult(place, itinerary);
    }

    private static PlaceCreate toPlaceCreate(VisionPlaceSuggestion suggestion) {
        PlaceType type = suggestion.type() == null ? PlaceType.ATTRACTION : suggestion.type();
        return new PlaceCreate(
                suggestion.name(),
                type,
                suggestion.address(),
                suggestion.latitude(),
                suggestion.longitude(),
                suggestion.imageUrl(),
                suggestion.rating(),
                suggestion.priceLevel(),
                suggestion.openingHours(),
                suggestion.sourceUrl(),
                null,
                null,
                null,
                null);
    }
}
