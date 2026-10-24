package com.huxizhen.travel.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.common.JsonResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未登录（或 token 失效）时的回答。
 *
 * <p>必须是我们自己的 401 + {@code {"detail": ...}}：Spring 默认给 403 或一个 HTML 登录页，
 * 前端只会看到"页面空白"。{@code WWW-Authenticate: Bearer} 也不能少。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String UNAUTHORISED_DETAIL = "请先登录后再操作。";

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        JsonResponses.writeError(
                objectMapper,
                response,
                HttpStatus.UNAUTHORIZED.value(),
                UNAUTHORISED_DETAIL,
                Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }
}
