package com.huxizhen.travel.dto.auth;

import com.huxizhen.travel.entity.User;
import java.time.LocalDateTime;

/** 账号的对外视图：字段与顺序都和 Pydantic 的 {@code UserRead} 一致。 */
public record UserRead(
        Long id,
        String username,
        String email,
        String phone,
        String displayName,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        LocalDateTime emailVerifiedAt,
        LocalDateTime phoneVerifiedAt) {

    public static UserRead from(User user) {
        return new UserRead(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getPhone(),
                user.getDisplayName(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getEmailVerifiedAt(),
                user.getPhoneVerifiedAt());
    }
}
