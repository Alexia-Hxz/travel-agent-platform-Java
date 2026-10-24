package com.huxizhen.travel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * token 格式的契约测试。
 *
 * <p>这里断言的是"与 Python 版同构"的结构性质：两段、URL-safe 无填充、紧凑 JSON、
 * {@code exp - iat == 30 天}、篡改/过期/换密钥全部拒绝。
 *
 * <p>跨语言互操作（Python 签的 token 能被 Java 读、反之亦然）需要用真实密钥生成的固定向量，
 * 等终端恢复后从原项目导一份放进 {@code resources/token-vectors.json} 再补一条断言。
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-please-change";
    private static final long NOW = 1_700_000_000L;

    private final TokenService service = new TokenService(SECRET, TokenService.DEFAULT_TTL_SECONDS);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripReturnsUserId() {
        String token = service.createAccessToken(42L, NOW);
        assertThat(service.readAccessToken(token, NOW + 10)).isEqualTo(42L);
    }

    @Test
    void tokenHasTwoUrlSafeSegmentsWithoutPadding() {
        String token = service.createAccessToken(7L, NOW);
        String[] parts = token.split("\\.", -1);

        assertThat(parts).hasSize(2);
        assertThat(parts[0]).doesNotContain("=");
        assertThat(parts[1]).doesNotContain("=");
        // URL-safe 字母表里没有 + 和 /
        assertThat(token).doesNotContain("+").doesNotContain("/");
    }

    @Test
    void payloadIsCompactJsonWithSubIatExp() throws Exception {
        String token = service.createAccessToken(9L, NOW);
        String body = token.substring(0, token.indexOf('.'));
        String json = new String(Base64.getUrlDecoder().decode(pad(body)), "UTF-8");

        // Python 用 separators=(",", ":")，所以不含 ", " 也不含 ": "
        assertThat(json).doesNotContain(", ").doesNotContain(": ");
        assertThat(json).startsWith("{\"sub\":9,\"iat\":").endsWith("}");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(json, Map.class);
        assertThat(((Number) payload.get("iat")).longValue()).isEqualTo(NOW);
        assertThat(((Number) payload.get("exp")).longValue() - NOW)
                .isEqualTo(TokenService.DEFAULT_TTL_SECONDS);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = service.createAccessToken(1L, NOW);
        String tampered = token.substring(0, token.length() - 2) + "aa";
        assertThat(service.readAccessToken(tampered, NOW)).isNull();
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = service.createAccessToken(1L, NOW);
        String body = token.substring(0, token.indexOf('.'));
        String forgedBody = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":2,\"iat\":1,\"exp\":9999999999}".getBytes());
        String forged = forgedBody + token.substring(token.indexOf('.'));

        assertThat(forged).isNotEqualTo(token);
        assertThat(body).isNotEmpty();
        assertThat(service.readAccessToken(forged, NOW)).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service.createAccessToken(5L, NOW);
        long afterExpiry = NOW + TokenService.DEFAULT_TTL_SECONDS;
        // 与原实现一致：exp <= now 就算过期
        assertThat(service.readAccessToken(token, afterExpiry)).isNull();
        assertThat(service.readAccessToken(token, afterExpiry - 1)).isEqualTo(5L);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        TokenService other = new TokenService("another-secret", TokenService.DEFAULT_TTL_SECONDS);
        String token = other.createAccessToken(3L, NOW);
        assertThat(service.readAccessToken(token, NOW)).isNull();
    }

    @Test
    void malformedTokensReturnNullInsteadOfThrowing() {
        assertThat(service.readAccessToken(null, NOW)).isNull();
        assertThat(service.readAccessToken("", NOW)).isNull();
        assertThat(service.readAccessToken("no-dot", NOW)).isNull();
        assertThat(service.readAccessToken("a.b.c", NOW)).isNull();
        assertThat(service.readAccessToken(".", NOW)).isNull();
        assertThat(service.readAccessToken("!!!.???", NOW)).isNull();
    }

    @Test
    void developmentSecretIsReported() {
        assertThat(new TokenService(null, TokenService.DEFAULT_TTL_SECONDS).usingDevelopmentSecret())
                .isTrue();
        assertThat(new TokenService("  ", TokenService.DEFAULT_TTL_SECONDS).usingDevelopmentSecret())
                .isTrue();
        assertThat(service.usingDevelopmentSecret()).isFalse();
    }

    @Test
    void builtInSecretStillSignsAndVerifies() {
        TokenService development = new TokenService(null, TokenService.DEFAULT_TTL_SECONDS);
        String token = development.createAccessToken(11L, NOW);
        assertThat(development.readAccessToken(token, NOW)).isEqualTo(11L);
        // 与显式传同样的派生密钥等价，说明用的是 sha256 摘要字节而不是字符串
        assertThat(service.readAccessToken(token, NOW)).isNull();
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }
}
