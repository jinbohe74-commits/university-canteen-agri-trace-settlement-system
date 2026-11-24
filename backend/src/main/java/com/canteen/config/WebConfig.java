package com.canteen.config;

import com.canteen.common.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置。
 *
 * <p>修复 P0-3：上传/下载接口不再免鉴权，也不再把 uploads 目录整体映射为公开静态资源，
 * 文件统一走 {@code FileController} 的受控下载接口。</p>
 * <p>修复 P2：CORS 白名单化，禁止任意站点携带凭证跨域。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Value("${canteen.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/roles",
                        "/api/common/health",
                        // EventSource 无法携带 Authorization，改用一次性票据在 Controller 内校验
                        "/api/common/events/stream",
                        "/error"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOriginPatterns(origins.length == 0 ? new String[]{"http://127.0.0.1:5173"} : origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
