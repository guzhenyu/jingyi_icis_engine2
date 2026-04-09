package com.jingyicare.jingyi_icis_engine.service.reports;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources.JfkDataSourceHandler;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources.JfkDataSourceSupport;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources.JfkPatientInfo;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;

@Service
@Slf4j
public class JfkDataService {
    public JfkDataService(
        @Autowired JfkDataSourceSupport support,
        @Autowired List<JfkDataSourceHandler> handlers
    ) {
        this.support = support;

        Map<String, JfkDataSourceHandler> nextHandlerMap = new LinkedHashMap<>();
        for (JfkDataSourceHandler handler : handlers) {
            JfkDataSourceHandler previous = nextHandlerMap.put(handler.getMetaId(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate JFK data source handler: " + handler.getMetaId()
                );
            }
        }
        this.handlerMap = Collections.unmodifiableMap(nextHandlerMap);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getDataSource(JfkDataSourcePB input) {
        JfkDataSourceHandler handler = handlerMap.get(input.getMetaId());
        if (handler == null) {
            log.error("Unsupported JFK data source metaId: {}", input.getMetaId());
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(support.getStatusMsgList(), StatusCode.INTERNAL_EXCEPTION),
                null
            );
        }
        return handler.handle(input);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getPatientInfo(JfkDataSourcePB input) {
        return getDataSource(input);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getWardReportSummary(JfkDataSourcePB input) {
        return getDataSource(input);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getWardReportPatients(JfkDataSourcePB input) {
        return getDataSource(input);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getRassDataSource(JfkDataSourcePB input) {
        return getDataSource(input);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getCpotDataSource(JfkDataSourcePB input) {
        return getDataSource(input);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getTestDataSource1(JfkDataSourcePB input) {
        return getDataSource(input);
    }

    public Pair<ReturnCode, JfkPatientInfo> getJfkPatientInfo(Long pid, LocalDateTime shiftTimeStart) {
        return support.getJfkPatientInfo(pid, shiftTimeStart);
    }

    private final JfkDataSourceSupport support;
    private final Map<String, JfkDataSourceHandler> handlerMap;
}
