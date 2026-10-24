package com.huxizhen.travel.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;

/** 在过滤器/拦截器里写 {@code {"detail": ...}} 的小工具（那里没有 @RestControllerAdvice）。 */
public final class JsonResponses {

    private JsonResponses() {
    }

    public static void writeError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            int status,
            String detail) throws IOException {
        writeError(objectMapper, response, status, detail, Map.of());
    }

    public static void writeError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            int status,
            String detail,
            Map<String, String> headers) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        headers.forEach(response::setHeader);
        objectMapper.writeValue(response.getWriter(), ApiException.detailBody(detail));
    }
}
