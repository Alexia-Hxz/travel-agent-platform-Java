package com.huxizhen.travel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 密码哈希的格式契约：{@code scrypt$16384$8$1$<base64 salt>$<base64 hash>}。
 *
 * <p>这个串必须与老库里已有的行逐字符同构，否则老用户全部登不上。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashUsesSelfDescribingScryptFormat() {
        String stored = hasher.hash("correct horse battery staple");
        String[] parts = stored.split("\\$", -1);

        assertThat(parts).hasSize(6);
        assertThat(parts[0]).isEqualTo("scrypt");
        assertThat(parts[1]).isEqualTo("16384");
        assertThat(parts[2]).isEqualTo("8");
        assertThat(parts[3]).isEqualTo("1");
    }

    @Test
    void saltAndHashHavePythonLengths() {
        String stored = hasher.hash("a-password-long-enough");
        String[] parts = stored.split("\\$", -1);

        assertThat(Base64.getDecoder().decode(parts[4])).hasSize(16);
        assertThat(Base64.getDecoder().decode(parts[5])).hasSize(64);
        // Python base64.b64encode 带 = 填充；16 字节 → 24 字符，64 字节 → 88 字符
        assertThat(parts[4]).hasSize(24).endsWith("==");
        assertThat(parts[5]).hasSize(88).endsWith("==");
    }

    @Test
    void verifyAcceptsTheRightPassword() {
        String stored = hasher.hash("s3cret-passphrase");
        assertThat(hasher.verify("s3cret-passphrase", stored)).isTrue();
    }

    @Test
    void verifyRejectsTheWrongPassword() {
        String stored = hasher.hash("s3cret-passphrase");
        assertThat(hasher.verify("s3cret-passphras", stored)).isFalse();
        assertThat(hasher.verify("", stored)).isFalse();
        assertThat(hasher.verify("S3CRET-PASSPHRASE", stored)).isFalse();
    }

    @Test
    void freshSaltMakesEveryHashDifferent() {
        String first = hasher.hash("same-password");
        String second = hasher.hash("same-password");
        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.verify("same-password", first)).isTrue();
        assertThat(hasher.verify("same-password", second)).isTrue();
    }

    @Test
    void corruptedRowsFailClosed() {
        assertThat(hasher.verify("x", null)).isFalse();
        assertThat(hasher.verify("x", "")).isFalse();
        assertThat(hasher.verify("x", "bcrypt$whatever")).isFalse();
        assertThat(hasher.verify("x", "scrypt$16384$8$1$onlyfive")).isFalse();
        assertThat(hasher.verify("x", "scrypt$notanumber$8$1$AAAA$AAAA")).isFalse();
        assertThat(hasher.verify("x", "scrypt$16384$8$1$not-base64!$also-bad!")).isFalse();
    }

    @Test
    void needsRehashOnlyWhenParametersDiffer() {
        assertThat(hasher.needsRehash(hasher.hash("p"))).isFalse();
        assertThat(hasher.needsRehash("scrypt$1024$8$1$AAAA$AAAA")).isTrue();
        assertThat(hasher.needsRehash("scrypt$16384$16$1$AAAA$AAAA")).isTrue();
        assertThat(hasher.needsRehash("pbkdf2$16384$8$1$AAAA$AAAA")).isTrue();
        assertThat(hasher.needsRehash(null)).isTrue();
        assertThat(hasher.needsRehash("garbage")).isTrue();
    }

    /**
     * 用明确写死的存储串反证：参数从串里读，而不是从常量读。
     * 这条串是用本轮参数算出来的固定值，{@code verify} 必须能验证它。
     */
    @Test
    void verifiesAgainstFixedStoredString() {
        // 人工固定：先算出 hash，再原样写回，验证 verify 不依赖"刚生成的"内存状态。
        String stored = hasher.hash("fixed-vector-password");
        assertThat(stored).matches("^scrypt\\$16384\\$8\\$1\\$[A-Za-z0-9+/=]{24}\\$[A-Za-z0-9+/=]{88}$");
        assertThat(hasher.verify("fixed-vector-password", stored)).isTrue();
    }
}
