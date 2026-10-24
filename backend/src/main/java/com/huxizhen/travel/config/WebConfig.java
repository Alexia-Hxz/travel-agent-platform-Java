package com.huxizhen.travel.config;

import com.huxizhen.travel.security.TripOwnershipInterceptor;
import com.huxizhen.travel.service.PhotoCacheService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 装配：静态资源与"行程归属"拦截器。
 *
 * <p>{@code /static/**} 必须公开可访问：前端用 {@code <img src="/static/place_images/hotel.svg">}
 * 渲染占位图，图片请求带不了 Authorization 头。占位图本身也不是隐私数据。
 *
 * <p>CORS 在 {@link SecurityConfig} 里配置：安全链比 MVC 更早处理预检请求，两边都配会导致
 * 响应头重复。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TripOwnershipInterceptor tripOwnershipInterceptor;
    private final PhotoCacheService photoCacheService;

    public WebConfig(
            TripOwnershipInterceptor tripOwnershipInterceptor, PhotoCacheService photoCacheService) {
        this.tripOwnershipInterceptor = tripOwnershipInterceptor;
        this.photoCacheService = photoCacheService;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);
        // 运行时镜像下来的照片放在 data/photo-cache，不在 jar 里。
        registry.addResourceHandler("/static/place_images/cache/**")
                .addResourceLocations(photoCacheService.directory().toUri().toString())
                .setCachePeriod(31536000);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只匹配 /trips/{id}/ 下面的路径：/trips 与 /trips/{id} 由各自的 service 校验。
        registry.addInterceptor(tripOwnershipInterceptor)
                .addPathPatterns("/api/v1/trips/*/**");
    }
}
