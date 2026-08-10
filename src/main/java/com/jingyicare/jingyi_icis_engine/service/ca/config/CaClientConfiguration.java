package com.jingyicare.jingyi_icis_engine.service.ca.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jingyicare.jingyi_icis_engine.service.ca.client.CigCaClient;
import com.jingyicare.jingyi_icis_engine.service.ca.client.GrpcCigCaClient;

@Configuration
@EnableConfigurationProperties(CaClientProperties.class)
public class CaClientConfiguration {
    @Bean(destroyMethod = "close")
    CigCaClient cigCaClient(CaClientProperties properties) {
        return new GrpcCigCaClient(properties);
    }
}
