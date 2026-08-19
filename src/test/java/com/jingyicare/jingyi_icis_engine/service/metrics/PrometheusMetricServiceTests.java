package com.jingyicare.jingyi_icis_engine.service.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PrometheusMetricServiceTests {
    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void apiMetricUsesOnlyApprovedLowCardinalityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrometheusMetricService service = new PrometheusMetricService(registry);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/patients/123456");
        request.setAttribute(
            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
            "/api/patients/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.recordApiMetrics("response", ignored -> ReturnCode.newBuilder()
            .setCode(StatusCode.OK.ordinal())
            .build());

        Meter meter = registry.find("jingyi_api_request_total").meter();
        assertNotNull(meter);
        Set<String> tagKeys = meter.getId().getTags().stream()
            .map(tag -> tag.getKey())
            .collect(Collectors.toSet());
        assertEquals(Set.of("path", "outcome", "code"), tagKeys);
        assertEquals("/api/patients/{id}", meter.getId().getTag("path"));
        assertEquals("success", meter.getId().getTag("outcome"));
        assertEquals(String.valueOf(StatusCode.OK.ordinal()), meter.getId().getTag("code"));
    }
}
