package com.jingyicare.jingyi_icis_engine.service.metrics;

import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
public class PrometheusMetricService {
    public PrometheusMetricService(
        MeterRegistry meterRegistry,
        @Autowired UserService userService
    ) {
        this.meterRegistry = meterRegistry;
        this.userService = userService;
    }

    public <T> T recordApiMetrics(T response, Function<T, ReturnCode> returnCodeExtractor) {
        // 获取当前用户信息
        Pair<String, String> userAccount = userService.getAccountWithAutoId();
        final String userId = (userAccount == null || StrUtils.isBlank(userAccount.getFirst())) ?
            "unknown" : userAccount.getFirst();
        final String userName = (userAccount == null || StrUtils.isBlank(userAccount.getSecond())) ?
            "unknown" : userAccount.getSecond();

        // 获取当前请求路径
        String requestPath = ServletUriComponentsBuilder.fromCurrentRequestUri().build().getPath();

        // 提取返回码
        ReturnCode returnCode = returnCodeExtractor.apply(response);
        final int statusCode = returnCode.getCode();
        final String outcome = statusCode == StatusCode.OK.ordinal() ? "success" : "failure";

        // 记录 Prometheus 指标
        Counter apiCounter = Counter.builder("jingyi_api_request_total")
            .tag("account_name", userName)         // 用户名
            .tag("path", requestPath)              // 请求路径
            .tag("outcome", outcome)               // 成功或失败
            .tag("code", String.valueOf(statusCode)) // 返回码
            .description("API 请求总数统计")
            .register(meterRegistry);
        apiCounter.increment();

        return response;
    }

    private final MeterRegistry meterRegistry;
    private final UserService userService;
}