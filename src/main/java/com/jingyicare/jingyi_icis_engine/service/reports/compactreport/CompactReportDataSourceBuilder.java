package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkAcTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkElementMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkPagePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTableColumnMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class CompactReportDataSourceBuilder {
    public List<JfkDataSourcePB> buildInputs(JfkTemplatePB template, MonitoringReportRequest request) {
        Set<String> metaIds = collectDataSourceMetaIds(template);
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (String metaId : metaIds) {
            inputs.add(JfkDataSourcePB.newBuilder()
                .setId("compact-" + metaId)
                .setMetaId(metaId)
                .addInputData(int64Input("pid", request.getPid() == null ? 0L : request.getPid()))
                .addInputData(strInput("dept_id", request.getDeptId()))
                .addInputData(strInput("shift_time", TimeUtils.toIso8601String(request.getShiftStartTime(), "UTC")))
                .addInputData(strInput("query_start", TimeUtils.toIso8601String(request.getQueryStartUtc(), "UTC")))
                .addInputData(strInput("query_end", TimeUtils.toIso8601String(request.getQueryEndUtc(), "UTC")))
                .build());
        }
        return inputs;
    }

    private Set<String> collectDataSourceMetaIds(JfkTemplatePB template) {
        Set<String> metaIds = new LinkedHashSet<>(template.getDataSourceMetaIdsList());
        for (JfkPagePB page : template.getPagesList()) {
            for (JfkTextPB text : page.getTextsList()) {
                addMeta(metaIds, text.getElementMeta(), "");
            }
            for (JfkTablePB table : page.getTablesList()) {
                addTableMetas(metaIds, table);
            }
            page.getContainersList().forEach(container -> {
                for (JfkAcTablePB acTable : container.getAcTablesList()) {
                    addTableMetas(metaIds, acTable.getTbl());
                }
                container.getAcTextsList().forEach(acText -> addMeta(metaIds, acText.getText().getElementMeta(), ""));
            });
        }
        metaIds.removeIf(StrUtils::isBlank);
        return metaIds;
    }

    private void addTableMetas(Set<String> metaIds, JfkTablePB table) {
        if (!StrUtils.isBlank(table.getDataSourceMetaId())) {
            metaIds.add(table.getDataSourceMetaId());
        }
        for (JfkTableColumnMetaPB columnMeta : table.getColumnMetasList()) {
            addMeta(metaIds, columnMeta.getElementMeta(), table.getDataSourceMetaId());
        }
    }

    private void addMeta(Set<String> metaIds, JfkElementMetaPB elementMeta, String defaultMetaId) {
        if (elementMeta == null || elementMeta.getContentType() != 4) return;
        String metaId = StrUtils.isBlank(elementMeta.getDataSourceMetaId())
            ? defaultMetaId
            : elementMeta.getDataSourceMetaId();
        if (!StrUtils.isBlank(metaId)) {
            metaIds.add(metaId);
        }
    }

    private JfkFieldDataPB strInput(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value == null ? "" : value).build())
            .build();
    }

    private JfkFieldDataPB int64Input(String id, long value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setInt64Val(value).build())
            .build();
    }
}
