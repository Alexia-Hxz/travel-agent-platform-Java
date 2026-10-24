package com.huxizhen.travel.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 访问 token 的签发与校验，字节级对齐 Python 版 {@code auth_service}。
 *
 * <p>**这不是标准 JWT**：原项目是两段自制格式
 *
 * <pre>
 * base64url({"sub":1,"iat":...,"exp":...}) + "." + base64url(HMAC-SHA256(secret, body))
 * </pre>
 *
 * <p>两个细节决定"老 token 还能不能用"：
 * <ul>
 *   <li>payload 用**紧凑分隔符**序列化（{@code {"sub":1,...}}，冒号逗号后无空格），
 *       与 Python {@code json.dumps(..., separators=(",", ":"))} 一致；多一个空格签名就不同；</li>
 *   <li>两段都用 **URL-safe Base64 且去掉 {@code =} 填充**。</li>
 * </ul>
 *
 * <p>签名只防篡改、不加密：payload 里有用户 id 和过期时间，本来也不是秘密。
 */
public class TokenService {

    /** 30 天。没有服务端 session 表，过期是唯一能结束会话的东西。 */
    public static final long DEFAULT_TTL_SECONDS = 30L * 24 * 60 * 60;

    private static final String FALLBACK_SECRET_SEED = "travel-agent-development-secret";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] secretKey;
    private final long ttlSeconds;
    private final boolean usingDevelopmentSecret;

    public TokenService(String configuredSecret, long ttlSeconds) {
        this.usingDevelopmentSecret = configuredSecret == null || configuredSecret.isBlank();
        this.secretKey = resolveSecret(configuredSecret);
        this.ttlSeconds = ttlSeconds;
    }

    public TokenService(String configuredSecret) {
        this(configuredSecret, DEFAULT_TTL_SECONDS);
    }

    /** 未配置 {@code AUTH_SECRET_KEY} 时用内置开发密钥；启动日志必须警告。 */
    public boolean usingDevelopmentSecret() {
        return usingDevelopmentSecret;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 签发 token。{@code nowSeconds} 用 epoch 秒，测试可注入。 */
    public String createAccessToken(long userId, long nowSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("iat", nowSeconds);
        payload.put("exp", nowSeconds + ttlSeconds);
        String body;
        try {
            body = BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("token payload could not be serialised", error);
        }
        return body + "." + BASE64_URL.encodeToString(sign(body));
    }

    public String createAccessToken(long userId) {
        return createAccessToken(userId, System.currentTimeMillis() / 1000L);
    }

    /**
     * 解析 token，返回用户 id；过期 / 截断 / 换密钥重签 / 根本不是 token 一律返回 {@code null}。
     * 调用方只把它变成 401，不能告诉客户端具体是哪种。
     */
    public Long readAccessToken(String token, long nowSeconds) {
        if (token == null) {
            return null;
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.') || separator == token.length() - 1) {
            return null;
        }
        String body = token.substring(0, separator);
        String signature = token.substring(separator + 1);
        try {
            if (!MessageDigest.isEqual(decodeUrlBase64(signature), sign(body))) {
                return null;
            }
            Map<?, ?> payload = objectMapper.readValue(decodeUrlBase64(body), Map.class);
            Object exp = payload.get("exp");
            Object sub = payload.get("sub");
            if (exp == null || sub == null) {
                return null;
            }
            // 与原实现一致：exp <= now 即过期。
            if (toLong(exp) <= nowSeconds) {
                return null;
            }
            return toLong(sub);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    public Long readAccessToken(String token) {
        return readAccessToken(token, System.currentTimeMillis() / 1000L);
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    private static byte[] decodeUrlBase64(String value) {
        int remainder = value.length() % 4;
        String padded = remainder == 0 ? value : value + "=".repeat(4 - remainder);
        return BASE64_URL_DECODER.decode(padded);
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static byte[] resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        try {
            // Python: hashlib.sha256(b"travel-agent-development-secret").digest()，拿摘要字节当 key。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(FALLBACK_SECRET_SEED.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
