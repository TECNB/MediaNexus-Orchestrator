package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenWebMvcConfig implements WebMvcConfigurer {

    private final AuthService authService;

    public SaTokenWebMvcConfig(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler
                    ) {
                        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                            return true;
                        }
                        authService.requireCurrentUser();
                        return true;
                    }
                })
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/emby/webhooks/playback",
                        "/api/v1/emby/webhooks/library",
                        "/api/v1/health"
                );
    }
}
