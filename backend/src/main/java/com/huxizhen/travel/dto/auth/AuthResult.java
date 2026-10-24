package com.huxizhen.travel.dto.auth;

/**
 * 注册/登录成功后的响应。
 *
 * <p>{@code claimedTripCount} 是认领的无主行程数：老数据没有主人，第一个登录的账号会接手，
 * 告诉用户"有几趟旅行刚归到你名下"能解释为什么列表不是空的。
 */
public record AuthResult(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserRead user,
        int claimedTripCount) {

    public static AuthResult of(String token, String tokenType, long ttl, UserRead user, int claimed) {
        return new AuthResult(token, tokenType, ttl, user, claimed);
    }
}
