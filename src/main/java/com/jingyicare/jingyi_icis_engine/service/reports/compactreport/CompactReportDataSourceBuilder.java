package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCommon.ReportMonGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCommon.ReportMedExeTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class CompactReportDataSourceBuilder {
    public List<JfkDataSourcePB> buildInputs(CompactReportTemplatePB compactTemplate, MonitoringReportRequest request) {
        JfkTemplatePB template = compactTemplate.getTemplate();
        Set<String> metaIds = collectDataSourceMetaIds(template);
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (String metaId : metaIds) {
            if (JfkDataSourceIds.isCompactTableScoped(metaId)) {
                continue;
            }
            inputs.add(commonInputBuilder(metaId, request)
                .setId("compact-" + metaId)
                .build());
        }
        inputs.addAll(buildPatientMonitoringRecordsInputs(compactTemplate, request));
        inputs.addAll(buildPatientBgaRecordsInputs(compactTemplate, request));
        inputs.addAll(buildPatientBalanceRecordsInputs(compactTemplate, request));
        inputs.addAll(buildMedexeRecordsInputs(compactTemplate, request));
        inputs.addAll(buildPatientTubeRecordsInputs(compactTemplate, request));
        inputs.addAll(buildPatientNonHourlyMonitoringRecordsInputs(compactTemplate, request));
        inputs.addAll(buildPatientNursingRecordsInputs(compactTemplate, request));
        inputs.addAll(buildPatientNursingOrdersInputs(compactTemplate, request));
        inputs.addAll(buildPatientSkincareRecordsInputs(compactTemplate, request));
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientMonitoringRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        Map<String, ReportMonGroupPB> monGroupByTableId = new LinkedHashMap<>();
        for (ReportMonGroupPB monGroup : compactTemplate.getMonGroupList()) {
            if (!StrUtils.isBlank(monGroup.getTableId())) {
                monGroupByTableId.putIfAbsent(monGroup.getTableId(), monGroup);
            }
        }

        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_MONITORING_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }
            ReportMonGroupPB monGroup = monGroupByTableId.get(table.getId());
            if (monGroup == null || monGroup.getParamCodeCount() == 0) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_MONITORING_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_MONITORING_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(strArrayInput("monitoring_param_codes", monGroup.getParamCodeList()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildMedexeRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        Map<String, ReportMedExeTablePB> medExeTableByTableId = new LinkedHashMap<>();
        for (ReportMedExeTablePB medExeTable : compactTemplate.getMedExeTablesList()) {
            if (!StrUtils.isBlank(medExeTable.getTableId())) {
                medExeTableByTableId.putIfAbsent(medExeTable.getTableId(), medExeTable);
            }
        }

        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.MEDEXE_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }
            ReportMedExeTablePB medExeTable = medExeTableByTableId.get(table.getId());
            if (medExeTable == null || medExeTable.getIntakeTypeId() <= 0) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.MEDEXE_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.MEDEXE_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(int64Input("intake_type_id", medExeTable.getIntakeTypeId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientBgaRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        if (StrUtils.isBlank(compactTemplate.getBgaTableId())) {
            return List.of();
        }

        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_BGA_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }
            if (!compactTemplate.getBgaTableId().equals(table.getId())) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_BGA_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_BGA_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientBalanceRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        Map<String, ReportMonGroupPB> balanceGroupByTableId = new LinkedHashMap<>();
        for (ReportMonGroupPB balanceGroup : compactTemplate.getBalanceGroupList()) {
            if (!StrUtils.isBlank(balanceGroup.getTableId())) {
                balanceGroupByTableId.putIfAbsent(balanceGroup.getTableId(), balanceGroup);
            }
        }

        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_BALANCE_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }
            ReportMonGroupPB balanceGroup = balanceGroupByTableId.get(table.getId());
            if (balanceGroup == null || balanceGroup.getParamCodeCount() == 0) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_BALANCE_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_BALANCE_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(strArrayInput("balance_param_codes", balanceGroup.getParamCodeList()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientTubeRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_TUBE_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_TUBE_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_TUBE_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientNursingRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_NURSING_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_NURSING_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_NURSING_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientNonHourlyMonitoringRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(
                    JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientNursingOrdersInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_NURSING_ORDERS.equals(table.getDataSourceMetaId())) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_NURSING_ORDERS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_NURSING_ORDERS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkDataSourcePB> buildPatientSkincareRecordsInputs(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputs = new ArrayList<>();
        for (JfkTablePB table : collectTables(compactTemplate.getTemplate())) {
            if (!JfkDataSourceIds.PATIENT_SKINCARE_RECORDS.equals(table.getDataSourceMetaId())) {
                continue;
            }

            inputs.add(commonInputBuilder(JfkDataSourceIds.PATIENT_SKINCARE_RECORDS, request)
                .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_SKINCARE_RECORDS, table.getId()))
                .addInputData(strInput("table_id", table.getId()))
                .addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
                .addInputData(doubleInput("font_size", table.getFontSize()))
                .addInputData(doubleInput("char_spacing", table.getCharSpacing()))
                .addInputData(doubleInput("h_padding", table.getHPadding()))
                .build());
        }
        return inputs;
    }

    private List<JfkTablePB> collectTables(JfkTemplatePB template) {
        List<JfkTablePB> tables = new ArrayList<>();
        for (JfkPagePB page : template.getPagesList()) {
            tables.addAll(page.getTablesList());
            for (var container : page.getContainersList()) {
                for (JfkAcTablePB acTable : container.getAcTablesList()) {
                    tables.add(acTable.getTbl());
                }
            }
        }
        return tables;
    }

    private JfkDataSourcePB.Builder commonInputBuilder(String metaId, MonitoringReportRequest request) {
        return JfkDataSourcePB.newBuilder()
            .setMetaId(metaId)
            .addInputData(int64Input("pid", request.getPid() == null ? 0L : request.getPid()))
            .addInputData(strInput("dept_id", request.getDeptId()))
            .addInputData(strInput("shift_time", TimeUtils.toIso8601String(request.getShiftStartTime(), "UTC")))
            .addInputData(strInput("query_start", TimeUtils.toIso8601String(request.getQueryStartUtc(), "UTC")))
            .addInputData(strInput("query_end", TimeUtils.toIso8601String(request.getQueryEndUtc(), "UTC")));
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

    private JfkFieldDataPB doubleInput(String id, double value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setDoubleVal(value).build())
            .build();
    }

    private JfkFieldDataPB strArrayInput(String id, List<String> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setStrVal(value == null ? "" : value).build())
                .toList())
            .build();
    }

    private JfkFieldDataPB doubleArrayInput(String id, List<Float> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setDoubleVal(value == null ? 0d : value.doubleValue()).build())
                .toList())
            .build();
    }
}
