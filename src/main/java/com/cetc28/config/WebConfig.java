package com.cetc28.config;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 注册管理接口拦截器，并把外部地图 SDK 目录映射为静态资源。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminGuard adminGuard;
    private final String mapAssetsDir;
    private final String plotImagesDir;

    public WebConfig(AdminGuard adminGuard,
                     @Value("${app.map.assets-dir:./map-assets}") String mapAssetsDir,
                     @Value("${app.plot.images-dir:./plot-images}") String plotImagesDir) {
        this.adminGuard = adminGuard;
        this.mapAssetsDir = mapAssetsDir;
        this.plotImagesDir = plotImagesDir;
    }

    /**
     * 前端页面/脚本不做强缓存：升级后刷新即可拿到新构建，避免继续运行旧版地图逻辑。
     */
    @Bean
    public FilterRegistrationBean<Filter> staticNoCacheFilter() {
        Filter filter = (request, response, chain) -> {
            String uri = ((HttpServletRequest) request).getRequestURI();
            boolean cacheable = uri.equals("/")
                    || uri.endsWith(".html")
                    || uri.endsWith(".js")
                    || uri.endsWith(".css")
                    || uri.endsWith(".json")
                    || uri.endsWith(".svg")
                    || uri.endsWith(".png")
                    || uri.endsWith(".jpg")
                    || uri.endsWith(".jpeg")
                    || uri.endsWith(".webp")
                    || uri.endsWith(".gif")
                    || uri.startsWith("/earth/plot-icons/")
                    || uri.startsWith("/plot-images/");
            if (cacheable && !uri.startsWith("/map-assets/")) {
                ((HttpServletResponse) response).setHeader("Cache-Control", "no-cache, must-revalidate");
                ((HttpServletResponse) response).setHeader("Pragma", "no-cache");
            }
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminGuard)
                .addPathPatterns(
                        "/api/mcp/**",
                        "/api/logs/**",
                        "/api/knowledge/**",
                        "/api/admin/**",
                        "/api/skills/refresh");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 便于直接打开 /earth，统一落到 Vue 子应用的入口页。
        registry.addRedirectViewController("/earth", "/earth/index.html");
        registry.addRedirectViewController("/earth/", "/earth/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Cesium/OpenLayers 体积大，默认放在 jar 外部（./map-assets）；也允许打进 classpath 兜底。
        String external = Paths.get(mapAssetsDir).toAbsolutePath().normalize().toUri().toString();
        if (!external.endsWith("/")) external = external + "/";
        registry.addResourceHandler("/map-assets/**")
                .addResourceLocations(external, "classpath:/static/map-assets/");

        // 标绘图标可放在 jar 外部目录（./plot-images），由前端用 /plot-images/xxx.png 引用。
        String plotExternal = Paths.get(plotImagesDir).toAbsolutePath().normalize().toUri().toString();
        if (!plotExternal.endsWith("/")) plotExternal = plotExternal + "/";
        registry.addResourceHandler("/plot-images/**")
                .addResourceLocations(plotExternal, "classpath:/static/plot-images/");
    }
}
