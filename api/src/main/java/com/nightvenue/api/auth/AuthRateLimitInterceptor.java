package com.nightvenue.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static com.nightvenue.api.auth.AuthExceptions.InvalidAccessToken;

@Configuration
public class AuthRateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final RateLimitService rateLimits;
    private final RateLimitProperties properties;

    public AuthRateLimitInterceptor(RateLimitService rateLimits, RateLimitProperties properties) {
        this.rateLimits = rateLimits;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/auth/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String scope;
        RateLimitProperties.Limit limit;
        String kind;
        String identity;

        if ("POST".equals(method) && path.endsWith("/login")) {
            scope = "login";
            limit = properties.getLogin();
        } else if ("POST".equals(method) && path.endsWith("/refresh")) {
            scope = "refresh";
            limit = properties.getRefresh();
        } else if ("POST".equals(method) && path.endsWith("/logout")) {
            scope = "logout";
            limit = properties.getLogout();
        } else if ("GET".equals(method) && path.endsWith("/me")) {
            scope = "me";
            limit = properties.getMe();
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
                throw new InvalidAccessToken();
            }
            kind = "user";
            identity = jwt.getSubject();
            rateLimits.check(scope, kind, identity, limit);
            return true;
        } else {
            return true;
        }

        kind = "ip";
        identity = request.getRemoteAddr();
        rateLimits.check(scope, kind, identity, limit);
        return true;
    }
}
