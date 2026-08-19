package com.jingyicare.jingyi_icis_engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "management.server.address=127.0.0.1",
        "management.server.port=0"
    })
@ActiveProfiles("test")
@AutoConfigureObservability
class ManagementEndpointBoundaryTests {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int businessPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void actuatorShouldExistOnlyOnManagementPort() throws Exception {
        for (String path : new String[] {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/prometheus"}) {
            assertNotEquals(200, get(businessPort, path).statusCode(), path);
        }

        for (String path : new String[] {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(managementPort, path);
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.body().contains("\"status\":\"UP\""), path);
            assertFalse(response.body().contains("\"components\""), path);
        }

        assertEquals(200, get(managementPort, "/actuator/prometheus").statusCode());
        assertNotEquals(200, get(managementPort, "/actuator/env").statusCode());
        assertNotEquals(200, get(managementPort, "/actuator/info").statusCode());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
