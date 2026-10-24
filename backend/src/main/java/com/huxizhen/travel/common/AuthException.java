package com.huxizhen.travel.common;

/** 账号相关错误的基类，对应 Python 的 {@code AuthError} 家族。 */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }

    /** 不是可用的用户名 / 邮箱 / 手机号。 */
    public static class InvalidIdentifier extends AuthException {
        public InvalidIdentifier(String message) {
            super(message);
        }
    }

    /** 密码太短或太长，不值得哈希。 */
    public static class InvalidPassword extends AuthException {
        public InvalidPassword(String message) {
            super(message);
        }
    }

    /** 这个标识已经被别的账号占用。 */
    public static class IdentifierTaken extends AuthException {
        public IdentifierTaken(String message) {
            super(message);
        }
    }
}
