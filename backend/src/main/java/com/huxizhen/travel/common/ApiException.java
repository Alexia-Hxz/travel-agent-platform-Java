package com.huxizhen.travel.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 业务错误，对应 Python 里的 {@code HTTPException(status_code=..., detail=...)}。
 *
 * <p>错误体统一为 {@code {"detail": "..."}}：Spring 默认的
 * {@code {timestamp,status,error,path}} 前端读不懂。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final transient HttpHeaders headers;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, new HttpHeaders());
    }

    public ApiException(HttpStatus status, String detail, HttpHeaders headers) {
        super(detail);
        this.status = status;
        this.headers = headers == null ? new HttpHeaders() : headers;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    /** 401 的响应必须带 {@code WWW-Authenticate: Bearer}，否则部分客户端不会重新登录。 */
    public static ApiException unauthorized(String detail) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return new ApiException(HttpStatus.UNAUTHORIZED, detail, headers);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    /** {@code {"detail": ...}} 的唯一构造入口，保证全局只有一种错误体。 */
    public static Map<String, Object> detailBody(Object detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", detail);
        return body;
    }
}
