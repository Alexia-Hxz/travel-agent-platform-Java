package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huxizhen.travel.common.AuthException;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.entity.User;
import com.huxizhen.travel.mapper.TripMapper;
import com.huxizhen.travel.mapper.UserMapper;
import com.huxizhen.travel.security.PasswordHasher;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号：标识识别、密码哈希、登录与行程认领。
 *
 * <p>逐条对齐 Python 版 {@code app/services/auth_service.py}。这里的**每条正则和每句文案**
 * 都是契约的一部分：前端会把这些文案直接展示给用户。
 */
@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{3,40}$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{6,20}$");
    private static final Pattern MAINLAND_MOBILE_PATTERN = Pattern.compile("^1[3-9][0-9]{9}$");

    /** 一个随机值的哈希：账号不存在时也校验一次，让"查无此人"和"密码错"耗时相同。 */
    private final String dummyHash;

    private final UserMapper userMapper;
    private final TripMapper tripMapper;
    private final PasswordHasher passwordHasher;

    public AuthService(UserMapper userMapper, TripMapper tripMapper, PasswordHasher passwordHasher) {
        this.userMapper = userMapper;
        this.tripMapper = tripMapper;
        this.passwordHasher = passwordHasher;
        this.dummyHash = passwordHasher.hash(Long.toHexString(System.nanoTime()));
    }

    /** 形状决定属于哪一列：有 @ 是邮箱，纯数字（可带 +）是手机号，其余是用户名。 */
    public String identifierKind(String value) {
        String candidate = value == null ? "" : value.strip();
        if (candidate.contains("@")) {
            return "email";
        }
        if (PHONE_PATTERN.matcher(candidate.replace(" ", "").replace("-", "")).matches()) {
            return "phone";
        }
        return "username";
    }

    /** 邮箱和小写、手机号去掉空格与横线；用户名只 trim。 */
    public String normaliseIdentifier(String value) {
        String candidate = value == null ? "" : value.strip();
        return switch (identifierKind(candidate)) {
            case "email" -> candidate.toLowerCase(Locale.ROOT);
            case "phone" -> candidate.replace(" ", "").replace("-", "");
            default -> candidate;
        };
    }

    /** 校验并归一化，返回 {@code [kind, normalised]}。 */
    public Identifier validateIdentifier(String value) {
        String candidate = normaliseIdentifier(value);
        String kind = identifierKind(candidate);
        switch (kind) {
            case "email" -> {
                if (!EMAIL_PATTERN.matcher(candidate).matches()) {
                    throw new AuthException.InvalidIdentifier("邮箱格式不正确，请检查后重试。");
                }
            }
            case "phone" -> {
                String digits = candidate.startsWith("+") ? candidate.substring(1) : candidate;
                if (!PHONE_PATTERN.matcher(candidate).matches()) {
                    throw new AuthException.InvalidIdentifier(
                            "手机号格式不正确，请填写 6-20 位数字（可带国家码 +86）。");
                }
                if (digits.startsWith("1") && digits.length() == 11
                        && !MAINLAND_MOBILE_PATTERN.matcher(digits).matches()) {
                    throw new AuthException.InvalidIdentifier("手机号格式不正确，请填写 11 位中国大陆手机号。");
                }
            }
            default -> {
                if (!USERNAME_PATTERN.matcher(candidate).matches()) {
                    throw new AuthException.InvalidIdentifier(
                            "用户名只能包含字母、数字、下划线、点和连字符，长度 3-40 位。");
                }
            }
        }
        return new Identifier(kind, candidate);
    }

    public String validatePassword(String password) {
        String value = password == null ? "" : password;
        if (value.length() < 8) {
            throw new AuthException.InvalidPassword("密码至少 8 位。");
        }
        if (value.length() > 128) {
            throw new AuthException.InvalidPassword("密码最长 128 位。");
        }
        return value;
    }

    public User findByIdentifier(String value) {
        Identifier identifier = validateIdentifier(value);
        var query = Wrappers.<User>lambdaQuery();
        switch (identifier.kind()) {
            case "email" -> query.eq(User::getEmail, identifier.value());
            case "phone" -> query.eq(User::getPhone, identifier.value());
            default -> query.eq(User::getUsername, identifier.value());
        }
        return userMapper.selectOne(query);
    }

    /** 注册。标识已存在时抛 {@link AuthException.IdentifierTaken}。 */
    @Transactional
    public User createUser(String identifier, String password) {
        Identifier parsed = validateIdentifier(identifier);
        validatePassword(password);
        if (findByIdentifier(parsed.value()) != null) {
            throw new AuthException.IdentifierTaken(takenMessage(parsed.kind()));
        }
        User user = new User();
        user.setPasswordHash(passwordHasher.hash(password));
        switch (parsed.kind()) {
            case "email" -> user.setEmail(parsed.value());
            case "phone" -> user.setPhone(parsed.value());
            default -> user.setUsername(parsed.value());
        }
        user.setFailedLoginCount(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException error) {
            // 两个注册请求同时抢同一个地址
            throw new AuthException.IdentifierTaken(takenMessage(parsed.kind()));
        }
        return userMapper.selectById(user.getId());
    }

    public String takenMessage(String kind) {
        return switch (kind) {
            case "email" -> "这个邮箱已经注册过了，直接登录即可。";
            case "phone" -> "这个手机号已经注册过了，直接登录即可。";
            default -> "这个用户名已经被占用了，换一个吧。";
        };
    }

    /** 校验标识与密码，成功返回账号，失败返回空。 */
    @Transactional
    public Optional<User> authenticate(String identifier, String password) {
        User user;
        try {
            user = findByIdentifier(identifier);
        } catch (AuthException.InvalidIdentifier error) {
            // 格式不对也只是"登录失败"，不能返回 422 告诉对方这种形状的输入存在与否。
            user = null;
        }
        if (user == null) {
            passwordHasher.verify(password == null ? "" : password, dummyHash);
            return Optional.empty();
        }
        if (!passwordHasher.verify(password == null ? "" : password, user.getPasswordHash())) {
            user.setFailedLoginCount((user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1);
            userMapper.updateById(user);
            return Optional.empty();
        }
        if (passwordHasher.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwordHasher.hash(password));
        }
        user.setFailedLoginCount(0);
        user.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(user);
        return Optional.of(userMapper.selectById(user.getId()));
    }

    /**
     * 把没有主人的行程交给这个账号，返回认领了几趟。
     *
     * <p>账号功能上线前的行程 {@code user_id IS NULL}；交给**第一个**登录的账号，
     * 才能让库里已有的行程不消失。之后的账号只看到自己的。
     */
    @Transactional
    public int claimUnownedTrips(User user) {
        List<Trip> orphans = tripMapper.selectList(Wrappers.<Trip>lambdaQuery().isNull(Trip::getUserId));
        if (orphans.isEmpty()) {
            return 0;
        }
        for (Trip trip : orphans) {
            trip.setUserId(user.getId());
            tripMapper.updateById(trip);
        }
        return orphans.size();
    }

    /** {@code [kind, value]} 的小载体。 */
    public record Identifier(String kind, String value) {
    }
}
