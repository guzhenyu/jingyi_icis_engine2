package com.jingyicare.jingyi_icis_engine.service.ca.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CaClientPropertiesTests {
    @Test
    void acceptsTheDefaultEnabledClientConfiguration() {
        CaClientProperties properties = new CaClientProperties();
        properties.setEnabled(true);

        assertDoesNotThrow(properties::afterPropertiesSet);
    }

    @Test
    void rejectsUnsafeEnabledClientConfiguration() {
        CaClientProperties properties = new CaClientProperties();
        properties.setEnabled(true);
        properties.setHost("0.0.0.0");
        assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);

        properties.setHost("127.0.0.1");
        properties.setPort(0);
        assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);

        properties.setPort(9089);
        properties.setGetSignImageDeadlineMs(60_001);
        assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);

        properties.setGetSignImageDeadlineMs(10_000);
        properties.setMaxImageBytes(10 * 1024 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
    }
}
