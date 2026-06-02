package com.jingyicare.jingyi_icis_engine.service.reports.ah2report;

import java.time.LocalDateTime;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public interface Ah2ReportDataProvider {
    String variant();

    Pair<ReturnCode, List<Ah2PageData>> collectPageData(
        Ah2PdfContext ctx,
        Long pid,
        String deptId,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        String accountId
    );
}
