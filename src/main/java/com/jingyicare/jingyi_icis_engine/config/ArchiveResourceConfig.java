package com.jingyicare.jingyi_icis_engine.config;

import java.nio.file.Path;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.jingyicare.jingyi_icis_engine.service.archives.ArchiveProperties;

@Configuration
public class ArchiveResourceConfig implements WebMvcConfigurer {
    public ArchiveResourceConfig(ArchiveProperties archiveProperties) {
        this.archiveProperties = archiveProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/archives/**")
            .addResourceLocations(rootLocation());
    }

    private String rootLocation() {
        String location = Path.of(archiveProperties.getRootPath())
            .toAbsolutePath()
            .normalize()
            .toUri()
            .toString();
        return location.endsWith("/") ? location : location + "/";
    }

    private final ArchiveProperties archiveProperties;
}
