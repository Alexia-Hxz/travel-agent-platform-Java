package com.huxizhen.travel.security;

import com.huxizhen.travel.entity.User;
import com.huxizhen.travel.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把 {@code Authorization: Bearer <token>} 解析成登录用户。
 *
 * <p>与原实现一致：**每个请求都重新读一次账号**。token 里的 id 指向一个已被删除的账号时，
 * 结果必须是 401 而不是"继续以幽灵身份操作"。
 *
 * <p>token 无效时这里**什么都不做**（不抛异常、不写响应），交给
 * {@link RestAuthenticationEntryPoint} 统一回答 401——因为公开路由（登录、注册、健康检查）
 * 同样会经过这个过滤器。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(TokenService tokenService, UserMapper userMapper) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Long userId = tokenService.readAccessToken(token);
            if (userId != null) {
                User user = userMapper.selectById(userId);
                if (user != null) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(user, null, List.of());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
