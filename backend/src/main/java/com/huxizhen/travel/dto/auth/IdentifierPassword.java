package com.huxizhen.travel.dto.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 注册与登录共用的入参。
 *
 * <p>{@code identifier} 故意叫得很泛：服务端自己判断它是用户名、邮箱还是手机号
 * （{@code AuthService#identifierKind}），这样登录表单不需要先问用户"你是哪种账号"。
 */
public record IdentifierPassword(
        @NotNull @Size(min = 3, max = 160) String identifier,
        @NotNull @Size(min = 1, max = 128) String password) {
}
