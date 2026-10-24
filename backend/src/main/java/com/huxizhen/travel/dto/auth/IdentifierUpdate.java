package com.huxizhen.travel.dto.auth;

import jakarta.validation.constraints.Size;

/** 给当前账号补一个邮箱或手机号。 */
public record IdentifierUpdate(
        @Size(max = 160) String email,
        @Size(max = 24) String phone) {
}
