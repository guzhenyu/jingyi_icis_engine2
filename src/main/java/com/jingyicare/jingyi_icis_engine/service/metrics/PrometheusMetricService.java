package com.jingyicare.jingyi_icis_engine.service.metrics;

import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

@Service
public class PrometheusMetricService {
    public PrometheusMetricService(
        MeterRegistry meterRegistry
    ) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T recordApiMetrics(T response, Function<T, ReturnCode> returnCodeExtractor) {
        // 只使用 Spring 已匹配的固定路由模板，避免把人员、患者或业务实体 ID 写入标签。
        String requestPath = resolveRequestPath();

        // 提取返回码
        ReturnCode returnCode = returnCodeExtractor.apply(response);
        final int statusCode = returnCode.getCode();
        final String outcome = statusCode == StatusCode.OK.ordinal() ? "success" : "failure";

        // 记录 Prometheus 指标
        Counter apiCounter = Counter.builder("jingyi_api_request_total")
            .tag("path", requestPath)              // 请求路径
            .tag("outcome", outcome)               // 成功或失败
            .tag("code", String.valueOf(statusCode)) // 返回码
            .description("API 请求总数统计")
            .register(meterRegistry);
        apiCounter.increment();

        return response;
    }

    private String resolveRequestPath() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes requestAttributes)) {
            return "unknown";
        }
        Object routePattern = requestAttributes.getRequest()
            .getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (routePattern == null || routePattern.toString().isBlank()) {
            return "unknown";
        }
        return routePattern.toString();
    }

    private final MeterRegistry meterRegistry;
}
