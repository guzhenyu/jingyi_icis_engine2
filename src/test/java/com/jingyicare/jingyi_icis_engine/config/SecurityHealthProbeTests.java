package com.jingyicare.jingyi_icis_engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@AutoConfigureMockMvc
class SecurityHealthProbeTests extends TestsBase {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void managementBoundaryShouldBeFixedAndExcludeDiagnosticEndpoints() throws Exception {
        assertEquals("0.0.0.0", environment.getProperty("server.address"));
        assertEquals("8080", environment.getProperty("server.port"));
        assertEquals("0.0.0.0", environment.getProperty("management.server.address"));
        assertEquals("9095", environment.getProperty("management.server.port"));
        assertEquals("health,prometheus",
            environment.getProperty("management.endpoints.web.exposure.include"));

        for (String path : new String[] {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/prometheus"}) {
            mockMvc.perform(get(path))
                .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/actuator/env").with(user("admin")))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/info").with(user("admin")))
            .andExpect(status().isNotFound());
    }
}
