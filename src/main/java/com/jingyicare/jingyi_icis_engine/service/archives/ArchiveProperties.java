package com.jingyicare.jingyi_icis_engine.service.archives;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
@ConfigurationProperties(prefix = "jingyi.archives")
@Data
public class ArchiveProperties {
    private String rootPath = "./archives/";

    public String getRootPath() {
        return StrUtils.isBlank(rootPath) ? "./archives/" : rootPath;
    }
}
