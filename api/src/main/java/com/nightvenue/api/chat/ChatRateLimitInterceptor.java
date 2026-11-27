package com.nightvenue.api.chat;

import com.nightvenue.api.auth.AuthExceptions.RedisUnavailable;
import com.nightvenue.api.auth.RateLimitProperties;
import com.nightvenue.api.auth.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class ChatRateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final RateLimitService rateLimits;
    private final ChatProperties chatProperties;

    public ChatRateLimitInterceptor(RateLimitService rateLimits, ChatProperties chatProperties) {
        this.rateLimits = rateLimits;
        this.chatProperties = chatProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/chat");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit(
                chatProperties.getRateLimitPerMin(), Duration.ofMinutes(1));
        try {
            rateLimits.check("chat", "ip", request.getRemoteAddr(), limit);
        } catch (RedisUnavailable ignored) {
            throw new ChatServiceUnavailable();
        }
        return true;
    }
}
