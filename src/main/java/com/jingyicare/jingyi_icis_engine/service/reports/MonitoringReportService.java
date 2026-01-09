package com.jingyicare.jingyi_icis_engine.service.reports;

import java.awt.geom.AffineTransform;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.util.*;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReport.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MonitoringReportService {
    public MonitoringReportService(
        ConfigurableApplicationContext context,
        @Value("${jingyi.textresources.font}") Resource fontResource,
        @Autowired ConfigProtoService protoService,
        @Autowired DepartmentRepository deptRepo,
        @Autowired PatientShiftRecordRepository shiftRecordRepo
    ) {
        try (InputStream is = fontResource.getInputStream()) {
            cachedFontBytes = is.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load resources: {} \n {}", e.getMessage(), e.getStackTrace());
            LogUtils.flushAndQuit(context);
        }

        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.PARAM_CODE_SUM = protoService.getConfig().getMonitoring().getParamCodeSummary();
        this.PARAM_CODE_SUM_TXT = protoService.getConfig().getMonitoring().getParamCodeSummaryTxt();
        this.ROTATION_DEGREE = -90;

        this.protoService = protoService;
        reportTemplate = protoService.getConfig().getMonitoringReport().getMonitoringTemplate();

        // report specs.
        pageX = reportTemplate.getLeftBottomX();
        pageY = reportTemplate.getLeftBottomY();
        pageWidth = reportTemplate.getWidth();
        pageHeight = reportTemplate.getHeight();

        // report.chart specs
        chart = reportTemplate.getChart();
        chartX = pageX;
        chartY = pageY + pageHeight - reportTemplate.getHeader().getHeight() - chart.getHeight();
        chartWidth = pageWidth;
        chartHeight = chart.getHeight();
        chartPaddingHorizontal = chart.getPaddingHorizontal();
        chartPaddingVertical = chart.getPaddingVertical();

        // report.chart.rulers specs
        rulers = chart.getRulers();
        rulersX = chartX + chartPaddingHorizontal;
        rulersY = chartY + chartPaddingVertical;
        rulerLength = chartHeight - chartPaddingVertical * 2;
        rulerMajorScales = rulers.getRulerMajorScales();
        rulerMajorScaleLength = rulers.getRulerMajorScaleLength();
        rulerMinorScales = rulers.getRulerMinorScales();
        rulerMinorScaleLength = rulers.getRulerMinorScaleLength();
        rulerFontSize = rulers.getRulerFontSize();
        rulerElemRightPadding = rulers.getElemRightPadding();

        // report.chart.grids specs
        chartGridsX = chart.getGridsLeftBottomX();
        chartGridsLeftMargin = chartGridsX - chartX;
        chartGridsY = rulersY;
        chartGridsWidth = chartWidth - chartGridsLeftMargin - chartPaddingHorizontal;
        chartGridsHeight = rulerLength;
        chartGridWidth = chartGridsWidth / HOURS_PER_DAY;
        chartGridHeight = chartGridsHeight / rulerMajorScales;
        chartGraphSignFontSize = chart.getGraphSignFontSize();

        // report.metric_grids specs
        metricGrids = reportTemplate.getMetricGrids();
        metricGridsX = pageX;
        metricGridsY = pageY + reportTemplate.getFooter().getHeight();
        metricGridsWidth = pageWidth;
        metricGridsHeight = pageHeight - reportTemplate.getHeader().getHeight() -
            chartHeight - reportTemplate.getFooter().getHeight();

        // report.metric_grids.grid specs
        metricGridWidth = chartGridWidth;
        metricGridHeight = metricGrids.getGridHeight();
        metricGridPaddingHorizontal = metricGrids.getGridPaddingHorizontal();
        metricGridFontSize = metricGrids.getFontSize();
        metricGridGroupWidth = metricGrids.getGroupWidth();
        metricGridNameWidth = metricGrids.getNameWidth();
        metricGridUnitWidth = chartGridsLeftMargin - metricGridGroupWidth - metricGridNameWidth;

        // line styles.
        lineStyleThick = reportTemplate.getLineStyleThick();
        lineStyleThin = reportTemplate.getLineStyleThin();
        lineStyleDotted = reportTemplate.getLineStyleDotted();

        // 签名
        this.SIGNATURE_CODE = reportTemplate.getSignatureCode();

        this.deptRepo = deptRepo;
        this.shiftRecordRepo = shiftRecordRepo;
    }

    public Pair<StatusCode, Integer> generateMonitoringReport(
        String reportPath, LocalDateTime shiftStartTime,
        Boolean isBalanceReport, PatientRecord patientRecord,
        GetPatientMonitoringGroupsResp groupsResp,
        GetPatientMonitoringRecordsResp recordsResp
    ) {
        ReportContext ctx = null;
        try (PDDocument doc = new PDDocument()) {
            Department dept = deptRepo.findByDeptIdAndIsDeletedFalse(patientRecord.getDeptId()).orElse(null);
            if (dept == null) {
                log.error("Failed to get department by deptId: {}", patientRecord.getDeptId());
                return new Pair<>(StatusCode.DEPARTMENT_NOT_FOUND, null);
            }

            ctx = initDoc(reportPath, shiftStartTime, isBalanceReport,
                patientRecord, dept, groupsResp, recordsResp, doc
            );
            initPdPage(ctx);
            drawHeader(ctx);
            drawChart(ctx);
            drawMetrics(ctx);
            endPdPage(ctx);
            endDoc(ctx);
        } catch (IOException e) {
            log.error("Failed to generate monitoring report: {} \n {}", e.getMessage(), e.getStackTrace());
            return new Pair<>(StatusCode.FAILED_TO_GENERATE_PDF_REPORT, null);
        }
        return new Pair<>(StatusCode.OK, ROTATION_DEGREE);
    }

    private ReportContext initDoc(
        String reportPath, LocalDateTime shiftStartTime,
        Boolean isBalanceReport,
        PatientRecord patientRecord,
        Department dept,
        GetPatientMonitoringGroupsResp groupsResp,
        GetPatientMonitoringRecordsResp recordsResp,
        PDDocument doc
    ) throws IOException {
        ReportContext ctx = new ReportContext();

        // 初始化基本输入信息
        ctx.pdfPath = reportPath;
        ctx.shiftStartTime = shiftStartTime;
        ctx.isBalanceReport = isBalanceReport;
        ctx.patientRecord = patientRecord;
        ctx.dept = dept;
        ctx.groups = groupsResp.getMonitoringGroupList();
        ctx.params = new HashMap<>();
        for (MonitoringGroupBetaPB group : ctx.groups) {
            for (MonitoringParamPB param : group.getParamList()) {
                ctx.params.put(param.getCode(), param);
            }
        }
        ctx.shouldDrawChart = false;
        for (RulerPB ruler : rulers.getRulerList()) {
            for (String code : ruler.getCodeList()) {
                MonitoringParamPB param = ctx.params.get(code);
                if (param != null) {
                    ctx.shouldDrawChart = true;
                    break;
                }
            }
            if (ctx.shouldDrawChart) break;
        }

        ctx.records = new HashMap<>();
        ctx.groupSumRecords = new HashMap<>();
        for (MonitoringGroupRecordsPB groupRecords : recordsResp.getGroupRecordsList()) {
            final Integer deptMonitoringGroupId = groupRecords.getDeptMonitoringGroupId();
            for (MonitoringCodeRecordsPB codeRecords : groupRecords.getCodeRecordsList()) {
                if (codeRecords.getParamCode().equals(PARAM_CODE_SUM)) {
                    ctx.groupSumRecords.put(deptMonitoringGroupId, codeRecords.getRecordValueList());
                    continue;
                }
                ctx.records.put(codeRecords.getParamCode(), codeRecords.getRecordValueList());
            }
        }

        // 初始化PDF元素
        ctx.document = doc;
        ctx.fontStream = new ByteArrayInputStream(cachedFontBytes);
        ctx.font = PDType0Font.load(ctx.document, ctx.fontStream);

        // 初始化图形指标元数据
        ctx.metricRulerMetaMap = new java.util.HashMap<>();

        // 初始化元素坐标
        // report.metric_grids.drawMetrics specs
        ctx.metricGridsTopY = ctx.shouldDrawChart ? chartY 
            : (pageY + pageHeight - reportTemplate.getHeader().getHeight());
        ctx.metricGridsGroupTopY = ctx.metricGridsTopY;
        ctx.metricGridsRowTopY = ctx.metricGridsTopY;
        ctx.metricGridsRowY = ctx.metricGridsTopY;

        return ctx;
    }

    private void endDoc(ReportContext ctx) throws IOException {
        ctx.document.save(ctx.pdfPath);
        ctx.document.close();
    }

    private void initPdPage(ReportContext ctx) throws IOException {
        ctx.pdPage = new PDPage(PDRectangle.A4);
        PDRectangle mediaBox = ctx.pdPage.getMediaBox();

        // Set crop box.
        final float pageMargin = 0f;
        mediaBox.setLowerLeftX(mediaBox.getLowerLeftX() + pageMargin);
        mediaBox.setLowerLeftY(mediaBox.getLowerLeftY() + pageMargin);
        mediaBox.setUpperRightX(mediaBox.getUpperRightX() - pageMargin);
        mediaBox.setUpperRightY(mediaBox.getUpperRightY() - pageMargin);
        ctx.pdPage.setCropBox(mediaBox);
        ctx.document.addPage(ctx.pdPage);

        // Init stream with affine transform. -90 degrees rotation.
        ctx.stream = new PDPageContentStream(ctx.document, ctx.pdPage);
        AffineTransform affineTrans = new AffineTransform(
            0, -1, 1, 0, 0, mediaBox.getHeight());
        Matrix matrix = new Matrix(affineTrans);
        ctx.stream.transform(matrix);

        ctx.firstGroupInPage = true;
    }

    private void endPdPage(ReportContext ctx) throws IOException {
        ctx.stream.close();
        ctx.metricGridsTopY = pageY + pageHeight - reportTemplate.getHeader().getHeight();
    }

    private void drawHeader(ReportContext ctx) throws IOException {
        HeaderPB header = reportTemplate.getHeader();

        // 打印大标题
        final String headerTxt = ctx.dept.getHospitalName() + " " + ctx.dept.getName() +
            (ctx.isBalanceReport ? header.getBalanceReportTitle() : header.getMonitoringReportTitle());
        final float headerTxtWidth = getStringWidthInPoints(ctx, headerTxt, header.getTitleFontSize());
        final float headerTxtX = pageX + (pageWidth - headerTxtWidth) / 2;
        final float headerTxtY = header.getTitleY();

        ctx.stream.beginText();
        ctx.stream.setFont(ctx.font, header.getTitleFontSize());
        ctx.stream.newLineAtOffset(headerTxtX, headerTxtY);
        ctx.stream.showText(headerTxt);
        ctx.stream.endText();

        // 打印病人属性
        StringBuilder line1Builder = new StringBuilder();
        for (String attrName : header.getLine1AttrList()) {
            String attrStr = getAttrStr(ctx, attrName);
            if (attrStr != null) line1Builder.append(attrStr).append("      ");
        }
        ctx.stream.beginText();
        ctx.stream.setFont(ctx.font, header.getAttrFontSize());
        ctx.stream.newLineAtOffset(pageX, header.getAttrYLine1());
        ctx.stream.showText(line1Builder.toString());
        ctx.stream.endText();

        StringBuilder line2Builder = new StringBuilder();
        for (String attrName : header.getLine2AttrList()) {
            String attrStr = getAttrStr(ctx, attrName);
            if (attrStr != null) line2Builder.append(attrStr).append("      ");
        }
        ctx.stream.beginText();
        ctx.stream.setFont(ctx.font, header.getAttrFontSize());
        ctx.stream.newLineAtOffset(pageX, header.getAttrYLine2());
        ctx.stream.showText(line2Builder.toString());
        ctx.stream.endText();
    }

    private void drawChart(ReportContext ctx) throws IOException {
        if (!ctx.shouldDrawChart) return;

        setLineStyle(ctx, lineStyleThick);
        ctx.stream.addRect(chartX, chartY, chartWidth, chartHeight);
        ctx.stream.stroke();
        
        float rulerX = chartX;
        List<RulerPB> rulerList = rulers.getRulerList();
        for (RulerPB ruler : rulerList) {
            rulerX = drawChartRuler(ctx, ruler, rulerX);
        }

        // Draw chart grids.
        setLineStyle(ctx, lineStyleDotted);
        for (int i = 0; i <= HOURS_PER_DAY; ++i) {
            final float lineX = chartGridsX + i * chartGridWidth;
            ctx.stream.moveTo(lineX, chartGridsY);
            ctx.stream.lineTo(lineX, chartGridsY + chartGridsHeight);
            ctx.stream.stroke();
        }
        for (int i = 0; i <= rulerMajorScales; ++i) {
            final float lineY = chartGridsY + i * chartGridHeight;
            ctx.stream.moveTo(chartGridsX, lineY);
            ctx.stream.lineTo(chartGridsX + chartGridsWidth, lineY);
            ctx.stream.stroke();
        }
    }

    // Return the new rulerX.
    // drawChart -> drawChartRuler
    private float drawChartRuler(ReportContext ctx, RulerPB ruler, float rulerX) throws IOException {
        List<String> codes = new ArrayList<>();
        List<Float> lowerBounds = new ArrayList<>();
        List<Float> upperBounds = new ArrayList<>();

        // 画图表的（一组）指标名称
        float codePaddingX = chartPaddingHorizontal;
        for (String code : ruler.getCodeList()) {
            MonitoringParamPB param = ctx.params.get(code);
            if (param == null) continue;
            rulerX += codePaddingX;
            codePaddingX = 0.0f;

            final String name = param.getName();
            final ValueMetaPB valueMeta = param.getValueMeta();
            final String unit = valueMeta.getUnit();
            final String chartSign = !StrUtils.isBlank(param.getChartSign()) ?
                param.getChartSign() : DEFAULT_CHART_SIGN;

            // Draw the graph sign
            ctx.stream.beginText();
            ctx.stream.setFont(ctx.font, rulerFontSize);
            ctx.stream.newLineAtOffset(rulerX, rulersY + rulerLength - rulerFontSize);
            ctx.stream.showText(chartSign);
            ctx.stream.endText();

            // Draw the metric name.
            rulerX += rulerFontSize;
            ctx.stream.saveGraphicsState();
            ctx.stream.beginText();
            {
                Matrix matrix = new Matrix();
                matrix.translate(rulerX, rulersY);
                matrix.rotate(Math.PI/2);
                ctx.stream.setTextMatrix(matrix);
            }
            ctx.stream.setFont(ctx.font, rulerFontSize);
            ctx.stream.newLineAtOffset(0, 0);
            ctx.stream.showText(name);

            // Draw the metric unit. "2 * fontSize": one space, one graph sign char.
            final float unitLen = getStringWidthInPoints(ctx, unit, rulerFontSize);
            ctx.stream.newLineAtOffset(rulerLength - unitLen - 2 * rulerFontSize, 0);
            ctx.stream.showText(unit);
            ctx.stream.endText();
            ctx.stream.restoreGraphicsState();

            // record ruler meta.
            codes.add(code);
            lowerBounds.add(ValueMetaUtils.convertToFloat(valueMeta.getLowerLimit(), valueMeta));
            upperBounds.add(ValueMetaUtils.convertToFloat(valueMeta.getUpperLimit(), valueMeta));
            ctx.metricRulerMetaMap.put(code, new MetricRulerMeta(
                0.0f, 0.0f, chartSign, getStringWidthInPoints(ctx, chartSign, chartGraphSignFontSize),
                chartGraphSignFontSize, valueMeta
            ));

            rulerX += rulerElemRightPadding;
        }
        if (codes.isEmpty()) return rulerX;

        ////// Draw chart ruler scale values.
        Float lowerBound = Collections.min(lowerBounds);
        Float upperBound = Collections.max(upperBounds);
        if (lowerBound >= upperBound) return rulerX;

        // 设置指标的上下限，用于计算某个指标的纵坐标位置
        ValueMetaPB rulerValueMeta = null;
        for (String code : codes) {
            MetricRulerMeta meta = ctx.metricRulerMetaMap.get(code);
            if (meta == null) {
                log.error("Failed to get metric ruler meta for code: {}", code);
                continue;
            }
            meta.lower = lowerBound;
            meta.upper = upperBound;
            rulerValueMeta = meta.valueMeta;
        }

        // 计算主刻度值，及其对应的字符串
        List<Float> scales = new ArrayList<>();
        List<String> scaleStrs = new ArrayList<>();
        List<Float> scaleStrLens = new ArrayList<>();
        Float step = (upperBound - lowerBound) / rulerMajorScales;
        for (int i = 0; i <= rulerMajorScales; i++) {
            Float scale = lowerBound + i * step;
            scales.add(scale);

            String scaleStr = ValueMetaUtils.extractAndFormatParamValue(
                ValueMetaUtils.convertFloatTo(scale, rulerValueMeta), rulerValueMeta
            );
            scaleStrs.add(scaleStr);

            scaleStrLens.add(getStringWidthInPoints(ctx, scaleStr, rulerFontSize));
        }

        // 打印主刻度
        float endRulerX = rulerX + Collections.max(scaleStrLens).floatValue();
        for (int i = 0; i <= rulerMajorScales; i++) {
            String scaleStr = scaleStrs.get(i);
            float xOffset = endRulerX - scaleStrLens.get(i);
            float yOffset = i == 0 ? rulersY :
                (i == rulerMajorScales ? rulersY + rulerLength - rulerFontSize :
                rulersY + rulerLength / rulerMajorScales * i - rulerFontSize / 2);

            ctx.stream.beginText();
            ctx.stream.setFont(ctx.font, rulerFontSize);
            ctx.stream.newLineAtOffset(xOffset, yOffset);
            ctx.stream.showText(scaleStr);
            ctx.stream.endText();
        }
        rulerX = endRulerX;

        // 画尺子
        final int numScales = rulerMajorScales * rulerMinorScales;
        final float scaleStepY = rulerLength / numScales;
        final float scaleMinorOffsetX = rulerMajorScaleLength - rulerMinorScaleLength;
        endRulerX = rulerX + rulerMajorScaleLength;
        for (int i = 0; i <= numScales; ++i) {
            final float scaleY = rulersY + scaleStepY * i;
            if (i % rulerMinorScales == 0) {
                ctx.stream.moveTo(rulerX, scaleY);
                ctx.stream.lineTo(endRulerX, scaleY);
            } else {
                ctx.stream.moveTo(rulerX + scaleMinorOffsetX, scaleY);
                ctx.stream.lineTo(endRulerX, scaleY);
            }
            ctx.stream.stroke();
        }
        ctx.stream.moveTo(endRulerX, rulersY);
        ctx.stream.lineTo(endRulerX, rulersY + rulerLength);
        ctx.stream.stroke();

        return endRulerX;
    }

    private void drawMetrics(ReportContext ctx) throws IOException {
        drawMetricsTimeRow(ctx);
        for (MonitoringGroupBetaPB groupMeta : ctx.groups) {
            final String groupName = groupMeta.getName();
            for (MonitoringParamPB paramMeta : groupMeta.getParamList()) {
                final String code = paramMeta.getCode();
                final List<MonitoringRecordValPB> records = ctx.records.get(code);
                drawMetricRow(
                    ctx, code, paramMeta.getName(), paramMeta.getValueMeta().getUnit(),
                    (records == null ? new ArrayList<>() : records), groupName
                );
            }
            final Integer deptMonitoringGroupId = groupMeta.getId();
            final List<MonitoringRecordValPB> sumRecords = ctx.groupSumRecords.get(deptMonitoringGroupId);
            if (sumRecords != null) {
                drawMetricRow(ctx, "", PARAM_CODE_SUM_TXT, "", sumRecords, groupName);
            }

            closeMetricGridsGroup(ctx, groupName);
        }

        // 画签名组
        drawMetricRow(ctx, SIGNATURE_CODE, "", "", getSignatureRecords(ctx), null);
        closeMetricGridsGroup(ctx, SIGNATURE_CODE);
    }

    // drawMetrics -> drawMetricRow
    // records不为null
    private void drawMetricRow(
        ReportContext ctx, String paramCode, String paramName, String unit,
        List<MonitoringRecordValPB> records, String groupName
    ) throws IOException {
        // 计算一行需要的高度
        int maxLines = 1;

        Map<String/*单元格内容*/, List<Integer>/*需要换行的字符下标*/> strLinesMap = new HashMap<>();
        for (MonitoringRecordValPB record : records) {
            final String cellStr = record.getValueStr();
            if (StrUtils.isBlank(cellStr) || strLinesMap.containsKey(cellStr)) continue;
            List<Integer> lineStartCharIndices = new ArrayList<>();
            lineStartCharIndices.add(0);

            float strWidth = 0;
            for (int i = 0; i < cellStr.length(); ++i) {
                strWidth += getStringWidthInPoints(ctx, cellStr.substring(i, i + 1), metricGridFontSize);
                if (strWidth > metricGridWidth - 2 * metricGridPaddingHorizontal) {
                    lineStartCharIndices.add(i);
                    strWidth = 0;
                }
            }
            strLinesMap.put(cellStr, lineStartCharIndices);
            maxLines = Math.max(maxLines, lineStartCharIndices.size());
        }
        final float rowHeight = (float) Math.ceil((maxLines * metricGridFontSize) / metricGridHeight) *
            metricGridHeight;

        // 如果必要，换页
        if (ctx.metricGridsRowTopY - rowHeight < metricGridsY) {
            closeMetricGridsGroup(ctx, groupName);
            endPdPage(ctx);
            initPdPage(ctx);
            drawMetricsTimeRow(ctx);
        }
        ctx.metricGridsRowY = ctx.metricGridsRowTopY - rowHeight;

        // 画指标名称，单位， 值
        if (!StrUtils.isBlank(paramName)) {
            ctx.stream.beginText();
            ctx.stream.setFont(ctx.font, metricGridFontSize);
            // metricGridsRowY + (metricGridsRowTopY - metricGridsRowY) / 2 - metricGridFontSize / 2 =
            // (metricGridsRowTopY + metricGridsRowY - metricGridFontSize) / 2
            ctx.stream.newLineAtOffset(
                metricGridsX + metricGridGroupWidth + metricGridPaddingHorizontal,
                (ctx.metricGridsRowTopY + ctx.metricGridsRowY - metricGridFontSize) / 2);
            ctx.stream.showText(paramName);
            ctx.stream.endText();
        }

        if (!StrUtils.isBlank(unit)) {
            ctx.stream.beginText();
            ctx.stream.setFont(ctx.font, metricGridFontSize);
            ctx.stream.newLineAtOffset(
                metricGridsX + metricGridGroupWidth + metricGridNameWidth + metricGridPaddingHorizontal,
                (ctx.metricGridsRowTopY + ctx.metricGridsRowY - metricGridFontSize) / 2);
            ctx.stream.showText(unit);
            ctx.stream.endText();
        }

        drawMetricRowValues(ctx, paramCode, records, strLinesMap);

        // 画横向分割线
        setLineStyle(ctx, lineStyleThin);
        ctx.stream.moveTo(metricGridsX + metricGridGroupWidth, ctx.metricGridsRowY);
        ctx.stream.lineTo(metricGridsX + metricGridsWidth, ctx.metricGridsRowY);
        ctx.stream.stroke();
        ctx.metricGridsRowTopY = ctx.metricGridsRowY;
    }

    // drawMetrics -> drawMetricsTimeRow
    // drawMetrics -> drawMetricRow -> drawMetricsTimeRow
    private void drawMetricsTimeRow(ReportContext ctx) throws IOException {
        final float rowHeight = metricGridHeight;
        final float rowY = ctx.metricGridsTopY - rowHeight;
        final float textOffsetY = (rowHeight - metricGridFontSize) / 2;
        for (int i = 0; i < HOURS_PER_DAY; ++i) {
            ctx.stream.beginText();
            ctx.stream.setFont(ctx.font, metricGridFontSize);
            ctx.stream.newLineAtOffset(
                chartGridsX + i * chartGridWidth + metricGridPaddingHorizontal,
                rowY + textOffsetY);
            int hour = (ctx.shiftStartTime.getHour() + i) % 24;
            ctx.stream.showText(String.format("%02d", hour) + ":00");
            ctx.stream.endText();
        }
        setLineStyle(ctx, lineStyleThick);
        ctx.stream.moveTo(metricGridsX, ctx.metricGridsTopY);
        ctx.stream.lineTo(metricGridsX + metricGridsWidth, ctx.metricGridsTopY);
        ctx.stream.moveTo(metricGridsX, rowY);
        ctx.stream.lineTo(metricGridsX + metricGridsWidth, rowY);
        ctx.stream.stroke();

        ctx.metricGridsGroupTopY = rowY;
        ctx.metricGridsRowTopY = rowY;
        ctx.metricGridsRowY = rowY;
    }

    // drawMetrics -> closeMetricGridsGroup
    // drawMetrics -> drawMetricRow -> closeMetricGridsGroup
    private void closeMetricGridsGroup(ReportContext ctx, String groupName) throws IOException {
        if (groupName == null || ctx.metricGridsGroupTopY <= ctx.metricGridsRowY) return;

        // 画组名
        ctx.stream.beginText();
        ctx.stream.setFont(ctx.font, metricGridFontSize);
        // metricGridsRowY + (metricGridsGroupTopY - metricGridsRowY) / 2 - metricGridFontSize / 2 =
        // (metricGridsGroupTopY + metricGridsRowY - metricGridFontSize) / 2
        ctx.stream.newLineAtOffset(
            metricGridsX + metricGridPaddingHorizontal,
            (ctx.metricGridsGroupTopY + ctx.metricGridsRowY - metricGridFontSize) / 2);
        ctx.stream.showText(groupName);
        ctx.stream.endText();

        setLineStyle(ctx, lineStyleThick);
        ctx.stream.moveTo(metricGridsX, ctx.metricGridsRowY);
        ctx.stream.lineTo(metricGridsX + metricGridsWidth, ctx.metricGridsRowY);
        ctx.stream.stroke();

        // 画垂直线(3)
        // 画垂直线（1/3 - 左右）
        setLineStyle(ctx, lineStyleThick);
        ctx.stream.moveTo(metricGridsX, ctx.metricGridsTopY);
        ctx.stream.lineTo(metricGridsX, ctx.metricGridsRowY);
        ctx.stream.stroke();
        final float metricGridsRightX = metricGridsX + metricGridsWidth;
        ctx.stream.moveTo(metricGridsRightX, ctx.metricGridsTopY);
        ctx.stream.lineTo(metricGridsRightX, ctx.metricGridsRowY);
        ctx.stream.stroke();

        // 画垂直线（2/3 - 组分割线，指标名分割线）
        if (!groupName.equals(SIGNATURE_CODE)) {
            setLineStyle(ctx, lineStyleThin);
            final float metricGridGroupRightX = metricGridsX + metricGridGroupWidth;
            final float topMargin = (ctx.firstGroupInPage ? metricGridHeight : 0);
            ctx.stream.moveTo(metricGridGroupRightX, ctx.metricGridsTopY - topMargin);
            ctx.stream.lineTo(metricGridGroupRightX, ctx.metricGridsRowY);
            ctx.stream.stroke();
            final float metricGridNameRightX = metricGridGroupRightX + metricGridNameWidth;
            ctx.stream.moveTo(metricGridNameRightX, ctx.metricGridsTopY - topMargin);
            ctx.stream.lineTo(metricGridNameRightX, ctx.metricGridsRowY);
            ctx.stream.stroke();
        }

        // 画垂直线（3/3 - 单元格分割线）
        for (int i = 0; i < HOURS_PER_DAY; ++i) {
            final float lineX = chartGridsX + i * chartGridWidth;
            ctx.stream.moveTo(lineX, ctx.metricGridsTopY);
            ctx.stream.lineTo(lineX, ctx.metricGridsRowY);
            ctx.stream.stroke();
        }

        // 更新状态
        ctx.metricGridsTopY = ctx.metricGridsRowY;
        ctx.metricGridsGroupTopY = ctx.metricGridsRowY;
        ctx.firstGroupInPage = false;
    }

    // drawMetrics -> drawMetricRow -> drawMetricRowValues
    // records不为null
    private void drawMetricRowValues(
        ReportContext ctx, String paramCode, List<MonitoringRecordValPB> records,
        Map<String, List<Integer>> strLinesMap
    ) throws IOException {
        if (records.isEmpty()) return;
        final float rowHeight = ctx.metricGridsRowTopY - ctx.metricGridsRowY;

        // 折线图状态
        MetricRulerMeta rulerMeta = ctx.metricRulerMetaMap.get(paramCode);
        float prevGraphSignX = -1;
        float prevGraphSignY = -1;

        for (MonitoringRecordValPB record : records) {
            // 检查时间是否合法
            LocalDateTime  recordTime = TimeUtils.fromIso8601String(
                record.getRecordedAtIso8601(), ZONE_ID);
            if (recordTime == null ||
                recordTime.getMinute() != 0 ||
                recordTime.getSecond() != 0
            ) continue;

            final int hourIndex = (int) java.time.Duration.between(ctx.shiftStartTime, recordTime).toHours();
            if (hourIndex < 0 || hourIndex >= HOURS_PER_DAY - 1) continue;

            // 找到单元格内容
            String cellStr = record.getValueStr();
            List<Integer> lineStartCharIndices = strLinesMap.get(cellStr);
            if (StrUtils.isBlank(cellStr)) continue;

            // 填充单元格
            final float offsetX = chartGridsX + hourIndex * chartGridWidth;
            float offsetY = ctx.metricGridsRowTopY -
                (rowHeight - lineStartCharIndices.size() * metricGridFontSize) / 2
                - metricGridFontSize;
            for (int i = 0; i < lineStartCharIndices.size(); ++i) {
                final int lineStart = lineStartCharIndices.get(i);
                final int lineEnd = i == lineStartCharIndices.size() - 1 ?
                    cellStr.length() : lineStartCharIndices.get(i + 1);
                final String lineStr = cellStr.substring(lineStart, lineEnd);

                ctx.stream.beginText();
                ctx.stream.setFont(ctx.font, metricGridFontSize);
                ctx.stream.newLineAtOffset(offsetX + metricGridPaddingHorizontal, offsetY);
                ctx.stream.showText(lineStr);
                ctx.stream.endText();
                offsetY -= metricGridFontSize;
            }

            // 画折线图
            if (rulerMeta != null) {
                final float floatVal = ValueMetaUtils.convertToFloat(record.getValue(), rulerMeta.valueMeta);
                if (rulerMeta.lower >= rulerMeta.upper) continue;
                final float graphSignY = rulersY + rulerLength * (
                    floatVal <= rulerMeta.lower ?
                        0 :
                        (floatVal >= rulerMeta.upper ?
                            1 :
                            (floatVal - rulerMeta.lower) / (rulerMeta.upper - rulerMeta.lower)
                        )
                );

                ctx.stream.beginText();
                ctx.stream.setFont(ctx.font, chartGraphSignFontSize);
                ctx.stream.newLineAtOffset(
                    offsetX + rulerMeta.signOffsetX, graphSignY + rulerMeta.signOffsetY);
                ctx.stream.showText(rulerMeta.sign);
                ctx.stream.endText();

                if (prevGraphSignX >= 0) {
                    setLineStyle(ctx, lineStyleThin);
                    ctx.stream.moveTo(prevGraphSignX, prevGraphSignY);
                    ctx.stream.lineTo(offsetX, graphSignY);
                    ctx.stream.stroke();
                }
                prevGraphSignX = offsetX;
                prevGraphSignY = graphSignY;
            }
        }
    }

    private void setLineStyle(ReportContext ctx, LineStylePB style) throws IOException {
        ctx.stream.setLineWidth(style.getWidth());
        ctx.stream.setStrokingColor(style.getGrayValue());

        float[] dashPattern;
        if (style.getDashPatternCount() == 0) {
            dashPattern = new float[0];
        } else {
            dashPattern = new float[2];
            dashPattern[0] = style.getDashPattern(0);
            dashPattern[1] = style.getDashPattern(1);
        }
        ctx.stream.setLineDashPattern(dashPattern, 0);
    }

    private float getStringWidthInPoints(ReportContext ctx, String str, float fontSize) throws IOException {
        return ctx.font.getStringWidth(str) * fontSize / 1000;
    }

    private List<MonitoringRecordValPB> getSignatureRecords(ReportContext ctx) {
        LocalDateTime shiftStartUtc = TimeUtils.getUtcFromLocalDateTime(ctx.shiftStartTime, ZONE_ID);
        List<PatientShiftRecord> shiftRecords = shiftRecordRepo.findByPidAndShiftStartBetweenAndIsDeletedFalse(
            ctx.patientRecord.getId(), shiftStartUtc, shiftStartUtc.plusDays(1)
        );
        Map<LocalDateTime, String> signatureMap = new HashMap<>();
        for (PatientShiftRecord shiftRecord : shiftRecords) {
            for (Integer i = 0;
                shiftRecord.getShiftStart().plusHours(i).isBefore(shiftRecord.getShiftEnd()) && i < HOURS_PER_DAY;
                i++
            ) {
                signatureMap.put(shiftRecord.getShiftStart().plusHours(i), shiftRecord.getShiftNurseName());
            }
        }
        List<MonitoringRecordValPB> records = new ArrayList<>();
        for (int i = 0; i < HOURS_PER_DAY; ++i) {
            LocalDateTime time = ctx.shiftStartTime.plusHours(i);
            LocalDateTime timeUtc = TimeUtils.getUtcFromLocalDateTime(time, ZONE_ID);
            String nurseName = signatureMap.get(timeUtc);
            if (nurseName == null) nurseName = "";
            records.add(
                MonitoringRecordValPB.newBuilder()
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(timeUtc, ZONE_ID))
                    .setValueStr(nurseName)
                    .build()
            );
        }
        return records;
    }

    private String getAttrStr(ReportContext ctx, String attrName) {
        switch (attrName) {
            case "patient_name":
                return "姓名: " + ctx.patientRecord.getIcuName();
            case "gender":
                return "性别: " + protoService.getGenderStr(ctx.patientRecord.getIcuGender());
            case "age":
                return "年龄：" + ctx.patientRecord.getAge();
            case "mrn":
                return "住院号：" + ctx.patientRecord.getHisMrn();
            case "bed_no":
                return "床号：" + ctx.patientRecord.getHisBedNumber();
            case "admission_time":
                return "入院时间：" + TimeUtils.getYMDTimeString(ctx.patientRecord.getAdmissionTime());
            case "report_date":
                return "报告日期：" + TimeUtils.getYMDTimeString(ctx.shiftStartTime);
        }
        return null;
    }

    private static class MetricRulerMeta {
        public MetricRulerMeta(
            Float upper, Float lower, String sign, float signWidth, int signFontSize, ValueMetaPB valueMeta
        ) {
            this.upper = upper;
            this.lower = lower;
            this.sign = sign;
            signOffsetX = - 0.5f * signWidth;
            signOffsetY = - 0.5f * signFontSize + 1;
            this.valueMeta = valueMeta;
        }

        public Float upper;
        public Float lower;
        public final String sign;
        public final float signOffsetX;
        public final float signOffsetY;
        public final ValueMetaPB valueMeta;
    }

    private static class ReportContext {
        public String pdfPath;
        public LocalDateTime shiftStartTime;
        public Boolean isBalanceReport;

        public PatientRecord patientRecord;
        public Department dept;
        public List<MonitoringGroupBetaPB> groups;
        public Map<String/*code*/, MonitoringParamPB> params;
        public Map<String/*code*/, List<MonitoringRecordValPB>> records;
        public Map<Integer/*dept_monitoring_group_id*/, List<MonitoringRecordValPB>> groupSumRecords;
        public Boolean shouldDrawChart;

        public InputStream fontStream;

        public PDDocument document;
        public PDType0Font font;
        public PDPage pdPage;
        public PDPageContentStream stream;

        private Map<String/*code*/, MetricRulerMeta> metricRulerMetaMap;

        // 画图标时的临时状态
        private float metricGridsTopY;
        private float metricGridsGroupTopY;
        private float metricGridsRowTopY;
        private float metricGridsRowY;
        private boolean firstGroupInPage = true;
    }

    private static final int HOURS_PER_DAY = 24;
    private static final String DEFAULT_CHART_SIGN = "●";

    private final String ZONE_ID;
    private final String PARAM_CODE_SUM;
    private final String PARAM_CODE_SUM_TXT;
    private final String SIGNATURE_CODE;
    private final int ROTATION_DEGREE;

    // report specs.
    private final float pageX;
    private final float pageY;
    private final float pageWidth;
    private final float pageHeight;

    // report.chart specs
    private final ChartPB chart;
    private final float chartX;
    private final float chartY;
    private final float chartWidth;
    private final float chartHeight;
    private final float chartPaddingHorizontal;
    private final float chartPaddingVertical;

    // report.chart.rulers specs
    private final RulersPB rulers;
    private final float rulersX;
    private final float rulersY;
    private final float rulerLength;
    private final int rulerMajorScales;
    private final float rulerMajorScaleLength;
    private final int rulerMinorScales;
    private final float rulerMinorScaleLength;
    private final int rulerFontSize;
    private final float rulerElemRightPadding;

    // report.chart.grids specs
    private final float chartGridsX;
    private final float chartGridsLeftMargin;
    private final float chartGridsY;
    private final float chartGridsWidth;
    private final float chartGridsHeight;
    private final float chartGridWidth;
    private final float chartGridHeight;
    private final int chartGraphSignFontSize;

    // report.metric_grids specs
    private final MetricGridsPB metricGrids;
    private final float metricGridsX;
    private final float metricGridsY;
    private final float metricGridsWidth;
    private final float metricGridsHeight;

    // report.metric_grids.grid specs
    private final float metricGridWidth;
    private final float metricGridHeight;
    private final float metricGridPaddingHorizontal;
    private final int metricGridFontSize;
    private final float metricGridGroupWidth;
    private final float metricGridNameWidth;
    private final float metricGridUnitWidth;

    // line styles.
    private LineStylePB lineStyleThick;
    private LineStylePB lineStyleThin;
    private LineStylePB lineStyleDotted;

    private byte[] cachedFontBytes;

    private ConfigProtoService protoService;
    private MonitoringReportTemplatePB reportTemplate;

    private DepartmentRepository deptRepo;
    private PatientShiftRecordRepository shiftRecordRepo;
}