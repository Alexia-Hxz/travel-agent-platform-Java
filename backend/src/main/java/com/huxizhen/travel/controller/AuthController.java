package com.huxizhen.travel.controller;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.common.AuthException;
import com.huxizhen.travel.dto.auth.AuthResult;
import com.huxizhen.travel.dto.auth.IdentifierPassword;
import com.huxizhen.travel.dto.auth.IdentifierUpdate;
import com.huxizhen.travel.dto.auth.UserRead;
import com.huxizhen.travel.entity.User;
import com.huxizhen.travel.mapper.UserMapper;
import com.huxizhen.travel.security.TokenService;
import com.huxizhen.travel.service.AuthService;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口：注册、登录、读取/补充当前账号。
 *
 * <p>状态码是契约：注册 **201**，登录 200，越权/未登录 401 且带 {@code WWW-Authenticate}。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final UserMapper userMapper;

    public AuthController(AuthService authService, TokenService tokenService, UserMapper userMapper) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    /**
     * 注册即登录：让人把同一个密码连打两遍是纯摩擦。
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResult> register(@Valid @RequestBody IdentifierPassword payload) {
        User user;
        try {
            user = authService.createUser(payload.identifier(), payload.password());
        } catch (AuthException.IdentifierTaken error) {
            throw ApiException.conflict(error.getMessage());
        } catch (AuthException.InvalidIdentifier | AuthException.InvalidPassword error) {
            throw ApiException.unprocessable(error.getMessage());
        }
        int claimed = authService.claimUnownedTrips(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authResult(user, claimed));
    }

    @PostMapping("/login")
    public AuthResult login(@Valid @RequestBody IdentifierPassword payload) {
        Optional<User> user = authService.authenticate(payload.identifier(), payload.password());
        if (user.isEmpty()) {
            // "查无此人"与"密码错"共用一句话：区分开来会让任何人都能探测哪些手机号注册过。
            throw ApiException.unauthorized("用户名/邮箱/手机号或密码不正确。");
        }
        int claimed = authService.claimUnownedTrips(user.get());
        return authResult(user.get(), claimed);
    }

    /** 当前 token 属于谁；前端靠它恢复会话。 */
    @GetMapping("/me")
    public UserRead me(@AuthenticationPrincipal User user) {
        return UserRead.from(user);
    }

    /**
     * 给当前账号补一个邮箱或手机号：用用户名注册的人，之后可以用任意一种方式登录。
     */
    @PatchMapping("/me")
    public UserRead updateMe(
            @AuthenticationPrincipal User user, @Valid @RequestBody IdentifierUpdate payload) {
        if (payload.email() != null) {
            AuthService.Identifier parsed = validateForField("email", payload.email());
            assertNotTaken(user, parsed);
            user.setEmail(parsed.value());
        }
        if (payload.phone() != null) {
            AuthService.Identifier parsed = validateForField("phone", payload.phone());
            assertNotTaken(user, parsed);
            user.setPhone(parsed.value());
        }
        userMapper.updateById(user);
        return UserRead.from(userMapper.selectById(user.getId()));
    }

    private AuthResult authResult(User user, int claimed) {
        String token = tokenService.createAccessToken(user.getId());
        return AuthResult.of(token, "bearer", tokenService.ttlSeconds(), UserRead.from(user), claimed);
    }

    private AuthService.Identifier validateForField(String field, String value) {
        AuthService.Identifier parsed;
        try {
            parsed = authService.validateIdentifier(value);
        } catch (AuthException.InvalidIdentifier error) {
            throw ApiException.unprocessable(error.getMessage());
        }
        if (!field.equals(parsed.kind())) {
            // 把手机号粘进邮箱框，不能悄悄在错误的列上再建一个账号。
            throw ApiException.unprocessable(
                    "email".equals(field) ? "邮箱格式不正确。" : "手机号格式不正确。");
        }
        return parsed;
    }

    private void assertNotTaken(User user, AuthService.Identifier parsed) {
        User existing = authService.findByIdentifier(parsed.value());
        if (existing != null && !existing.getId().equals(user.getId())) {
            throw ApiException.conflict(authService.takenMessage(parsed.kind()));
        }
    }
}
