package com.huxizhen.travel.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局错误处理，输出与 FastAPI 一致的两种形状：
 *
 * <ul>
 *   <li>业务错误：{@code {"detail": "中文文案"}}</li>
 *   <li>参数校验失败：{@code {"detail": [{"type","loc","msg","input"}]}}（FastAPI/Pydantic 的 422 结构）</li>
 * </ul>
 *
 * <p>前端 {@code readDetail()} 只认字符串形态；列表形态会落到调用方自己的兜底文案，
 * 因此两种形状都能跑，但这里仍按 FastAPI 的结构输出，让"逐字节一致"这条路留着。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException error) {
        return ResponseEntity.status(error.getStatus())
                .headers(error.getHeaders())
                .body(ApiException.detailBody(error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(MethodArgumentNotValidException error) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (FieldError fieldError : error.getBindingResult().getFieldErrors()) {
            details.add(validationItem(fieldError));
        }
        if (details.isEmpty()) {
            details.add(validationItem("body", "value_error", "请求参数不合法。"));
        }
        return ResponseEntity.unprocessableEntity().body(ApiException.detailBody(details));
    }

    /**
     * Spring 6.1 起，{@code @RequestParam} 上的约束由框架直接校验并抛这个异常。
     * 必须自己接住，否则会落到 500。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(
            HandlerMethodValidationException error) {
        List<Map<String, Object>> details = new ArrayList<>();
        error.getAllValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(message -> details.add(
                    validationItem(parameter == null ? "query" : parameter,
                            "value_error", message.getDefaultMessage())));
        });
        if (details.isEmpty()) {
            details.add(validationItem("query", "value_error", "请求参数不合法。"));
        }
        return ResponseEntity.unprocessableEntity().body(ApiException.detailBody(details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleParamValidation(ConstraintViolationException error) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : error.getConstraintViolations()) {
            String path = String.valueOf(violation.getPropertyPath());
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            details.add(validationItem(
                    field, "value_error", violation.getMessage()));
        }
        if (details.isEmpty()) {
            details.add(validationItem("query", "value_error", "请求参数不合法。"));
        }
        return ResponseEntity.unprocessableEntity().body(ApiException.detailBody(details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException error) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiException.detailBody(List.of(
                        validationItem("body", "json_invalid", "请求体不是合法的 JSON。"))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            MissingServletRequestParameterException error) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiException.detailBody(List.of(
                        validationItem(error.getParameterName(), "missing", "缺少必填参数。"))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiException.detailBody(List.of(
                        validationItem(error.getName(), "int_parsing", "参数类型不正确。"))));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiException.detailBody("不支持这个请求方法。"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiException.detailBody("不支持的请求内容类型。"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.badRequest()
                .body(ApiException.detailBody("文件大小不能超过 10 MB。"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandler(NoHandlerFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiException.detailBody("接口不存在。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error, HttpServletRequest request) {
        // 追踪 id 让 500 在日志里能对上，但绝不把堆栈泄露给前端。
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled error [{}] {} {}", traceId, request.getMethod(), request.getRequestURI(), error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiException.detailBody("服务器内部错误，请稍后重试。（追踪号 " + traceId + ")"));
    }

    private static Map<String, Object> validationItem(FieldError fieldError) {
        String field = fieldError.getField();
        List<Object> loc = new ArrayList<>();
        loc.add("body");
        for (String part : field.split("\\.")) {
            loc.add(part);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", pydanticType(fieldError.getCode()));
        item.put("loc", loc);
        item.put("msg", fieldError.getDefaultMessage());
        return item;
    }

    private static Map<String, Object> validationItem(String field, String type, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("loc", List.of("body", field));
        item.put("msg", message);
        return item;
    }

    /** Jakarta 的约束码 → Pydantic v2 的 {@code type} 值，尽量让两边的 422 长得一样。 */
    private static String pydanticType(String code) {
        if (code == null) {
            return "value_error";
        }
        return switch (code) {
            case "NotNull" -> "missing";
            case "NotBlank", "NotEmpty" -> "string_too_short";
            case "Size" -> "string_too_short";
            case "Min" -> "greater_than_equal";
            case "Max" -> "less_than_equal";
            case "Positive" -> "greater_than";
            case "PositiveOrZero" -> "greater_than_equal";
            case "Negative" -> "less_than";
            case "Pattern" -> "string_pattern_mismatch";
            case "Email" -> "value_error";
            default -> "value_error";
        };
    }
}
