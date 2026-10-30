package com.jingyicare.jingyi_icis_engine.testutils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreSqlTestConfiguration {
    // Spring reuses this container with the cached context and isolates other contexts.
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer(
            @Value("${jingyi.test.postgresql.database-name:jingyi_icis_test}") String databaseName) {
        return new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName(databaseName);
    }
}
