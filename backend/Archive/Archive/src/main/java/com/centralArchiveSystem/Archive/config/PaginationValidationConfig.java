package com.centralArchiveSystem.Archive.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/** Reject invalid input before any controller silently clamps it. Existing upper caps remain. */
@Configuration
public class PaginationValidationConfig implements WebMvcConfigurer {
    private static final Set<String> PAGINATION_PARAMETERS = Set.of("page", "size", "limit");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) {
                if (!(handler instanceof HandlerMethod method) || !"GET".equals(request.getMethod())) {
                    return true;
                }
                for (MethodParameter parameter : method.getMethodParameters()) {
                    RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
                    if (annotation == null) continue;
                    String name = !annotation.name().isBlank() ? annotation.name()
                            : !annotation.value().isBlank() ? annotation.value() : parameter.getParameterName();
                    if (name == null || !PAGINATION_PARAMETERS.contains(name)) continue;
                    String[] values = request.getParameterValues(name);
                    if (values == null) continue;
                    for (String value : values) {
                        try {
                            if (Integer.parseInt(value) < 1) throw new NumberFormatException();
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException(
                                    "قيمة " + name + " يجب أن تكون عدداً صحيحاً موجباً");
                        }
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
}
