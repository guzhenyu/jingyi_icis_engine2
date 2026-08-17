package com.jingyicare.jingyi_icis_engine.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@AutoConfigureMockMvc
class SecurityHealthProbeTests extends TestsBase {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthProbesShouldExposeOnlyStatusWithoutAuthentication() throws Exception {
        for (String probe : new String[] {"liveness", "readiness"}) {
            mockMvc.perform(get("/actuator/health/" + probe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        }
    }

    @Test
    void aggregateHealthShouldStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().is3xxRedirection());
    }
}
