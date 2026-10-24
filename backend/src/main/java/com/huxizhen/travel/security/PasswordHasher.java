package com.huxizhen.travel.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Component;

/**
 * 密码哈希，字节级对齐 Python 版 {@code app/services/auth_service.py}。
 *
 * <p>存储格式是**自描述**的，且必须与老库里的行完全一致，否则所有老用户都登不上：
 *
 * <pre>
 * scrypt$16384$8$1$&lt;base64(salt)&gt;$&lt;base64(derived)&gt;
 * </pre>
 *
 * <p>三个要点：
 * <ul>
 *   <li>{@code salt} 是 16 字节，{@code derived} 是 64 字节；</li>
 *   <li>两段 Base64 用**标准字母表 + 带 {@code =} 填充**（Python {@code base64.b64encode}），
 *       不是 URL-safe、不是无填充；</li>
 *   <li>参数从存储串里读，不假设当前常量——这正是 {@code needs_rehash} 能工作的前提。</li>
 * </ul>
 */
@Component
public class PasswordHasher {

    /** n=2^14 与 r=8 约需 16 MB 内存：足够慢，登录仍然即时。 */
    public static final int CURRENT_N = 1 << 14;
    public static final int CURRENT_R = 8;
    public static final int CURRENT_P = 1;
    public static final int DERIVED_KEY_LENGTH = 64;
    public static final int SALT_LENGTH = 16;

    private static final String SCHEME = "scrypt";
    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

    private final SecureRandom random = new SecureRandom();

    /** 用当前参数哈希一个新密码。每次调用都产生新的随机 salt。 */
    public String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, CURRENT_N, CURRENT_R, CURRENT_P, DERIVED_KEY_LENGTH);
        return String.join(
                "$",
                SCHEME,
                String.valueOf(CURRENT_N),
                String.valueOf(CURRENT_R),
                String.valueOf(CURRENT_P),
                BASE64.encodeToString(salt),
                BASE64.encodeToString(derived));
    }

    /**
     * 校验密码。任何格式异常都返回 {@code false}（fail closed），绝不抛出：
     * 一条被手工改坏的旧行不能让登录接口 500。
     */
    public boolean verify(String password, String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 6 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int n = Integer.parseInt(parts[1]);
            int r = Integer.parseInt(parts[2]);
            int p = Integer.parseInt(parts[3]);
            byte[] salt = BASE64_DECODER.decode(parts[4]);
            byte[] expected = BASE64_DECODER.decode(parts[5]);
            byte[] actual = derive(password, salt, n, r, p, expected.length);
            return constantTimeEquals(actual, expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 存储串用的参数弱于当前参数时为 true，登录时顺手升级。 */
    public boolean needsRehash(String stored) {
        if (stored == null) {
            return true;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 6 || !SCHEME.equals(parts[0])) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) != CURRENT_N
                    || Integer.parseInt(parts[2]) != CURRENT_R
                    || Integer.parseInt(parts[3]) != CURRENT_P;
        } catch (NumberFormatException error) {
            return true;
        }
    }

    private static byte[] derive(String password, byte[] salt, int n, int r, int p, int length) {
        return SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, n, r, p, length);
    }

    private static boolean constantTimeEquals(byte[] first, byte[] second) {
        if (first.length != second.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < first.length; index++) {
            difference |= first[index] ^ second[index];
        }
        return difference == 0;
    }
}
