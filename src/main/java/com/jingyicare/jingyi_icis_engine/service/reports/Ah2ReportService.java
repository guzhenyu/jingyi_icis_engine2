package com.jingyicare.jingyi_icis_engine.service.reports;

import java.awt.Color;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.google.protobuf.TextFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.util.*;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReport.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class Ah2ReportService {
    public Ah2ReportService(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired JfkDataService jfkDataService,
        @Value("${report_ah2}") Resource ah2TemplateResource,
        @Value("${jingyi.textresources.charset}") String charsetName,
        @Value("${jingyi.textresources.font}") Resource fontResource,
        @Autowired Ah2ReportData ah2ReportData
    ) {
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.jfkDataService = jfkDataService;

        ReportTemplateAh2PB parsedTemplate = null;
        try {  // 初始化模板信息
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                ah2TemplateResource.getInputStream(), charsetName));
            ReportTemplateAh2PB.Builder templateBuilder = ReportTemplateAh2PB.newBuilder();
            TextFormat.getParser().merge(reader, templateBuilder);

            // 检查模板是否规范：表头是否有顶层元素，整体宽度（/高度）是否超出范围...
            Pair<Boolean, ReportTemplateAh2PB> normalizedPair = normalizeTemplate(templateBuilder.build());
            if (!normalizedPair.getFirst()) {
                log.error("Invalid template provided.");
                LogUtils.flushAndQuit(context);
            }
            parsedTemplate = normalizedPair.getSecond();
            reader.close();
            log.info("Config loaded successfully");
        } catch (IOException e) {
            log.error("Failed to load text resource: " + e.getMessage() + "\n" + e.getStackTrace());
            LogUtils.flushAndQuit(context);
        }
        this.template = parsedTemplate;

        // 初始化字体
        try (InputStream is = fontResource.getInputStream()) {
            fontDataBytes = is.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load resources from {}: {} \n {}", fontResource, e.getMessage(), e.getStackTrace());
            this.fontDataBytes = null;
            LogUtils.flushAndQuit(context);
        }

        this.ah2ReportData = ah2ReportData;
    }

    public ReturnCode drawPdf(
        Long pid, String deptId, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        String accountId, String outputPath
    ) {
        if (template == null) {
            log.error("No valid template to draw PDF.");
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_PARAM_VALUE);
        }

        try (PDDocument document = new PDDocument()) {  // try-with-resources 会在退出作用域时自动调用 close()
            // 设置上下文
            Ah2PdfContext ctx = new Ah2PdfContext();
            ctx.pid = pid;
            ctx.document = document;
            ctx.font = PDType0Font.load(ctx.document, new ByteArrayInputStream(fontDataBytes));
            TableCommonPB tblCommonPb = this.template.getPage().getTblCommon();
            ctx.tblCommon = tblCommonPb;
            ctx.colMetaMap = new HashMap<>();
            for (ParamColMetaPB colMeta : tblCommonPb.getParamColMetaList()) {
                ctx.colMetaMap.put(colMeta.getParamCode(), colMeta);
            }
            ctx.tblTxtStyle = tblCommonPb.getTextStyle();
            ctx.tblLineStyle = tblCommonPb.getLineStyle();

            // 收集数据
            Pair<ReturnCode, List<Ah2PageData>> dataPair = ah2ReportData.collectPageData(
                ctx, pid, deptId, queryStartUtc, queryEndUtc, accountId
            );
            ReturnCode returnCode = dataPair.getFirst();
            if (returnCode.getCode() != StatusCode.OK.ordinal()) {
                return returnCode;
            }
            List<Ah2PageData> pageDataList = dataPair.getSecond();

            // 渲染pdf
            PagePB pagePb = template.getPage();
            for (int i = 0; i < pageDataList.size(); ++i) {
                Ah2PageData pageData = pageDataList.get(i);
                ctx.pageData = pageData;
                drawLogicalPage(ctx, pagePb);
            }

            document.save(outputPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK);
    }

    private static class TableCommonContext {
        // 表格属性
        public TextStylePB tableTextStyle;
        public float tableLeft;
        public float tableBottom;
        public float tableWidth;
        public float tableHeight;
        public float tableRight;
        public float tableTop;

        public float rowHeight;
        public int headerRows;
        public int bodyRows;

        public List<ParamColMetaPB> paramColMetaList;

        public TableCommonContext(TableCommonPB commonPb) {
            this.tableTextStyle = commonPb.getTextStyle();
            this.tableLeft = commonPb.getLeft();
            this.tableBottom = commonPb.getBottom();
            this.tableWidth = commonPb.getWidth();
            this.tableHeight = commonPb.getRowHeight() * (commonPb.getHeaderRows() + commonPb.getBodyRows());
            this.tableRight = commonPb.getLeft() + commonPb.getWidth();
            this.tableTop = commonPb.getBottom() + this.tableHeight;

            this.rowHeight = commonPb.getRowHeight();
            this.headerRows = commonPb.getHeaderRows();
            this.bodyRows = commonPb.getBodyRows();
            this.paramColMetaList = new ArrayList<>();
        }
    }

    private Pair<Boolean, ReportTemplateAh2PB> normalizeTemplate(ReportTemplateAh2PB template) {
        if (template == null) {
            return new Pair<>(false, null);
        }
        ReportTemplateAh2PB.Builder builder = ReportTemplateAh2PB.newBuilder();
        builder.setName(template.getName());
        TableCommonPB tblCommonPb = template.getPage().getTblCommon();
        TableCommonContext ctx = new TableCommonContext(tblCommonPb);

        List<SubPagePB> subPages = new ArrayList<>();
        for (SubPagePB subPage : template.getPage().getSubPageList()) {
            TablePB tablePb = subPage.getTable();

            // 每个表格必须有顶层表头
            if (tablePb.getHeader().getTopLevelCellCount() == 0) {
                log.error("No top-level header cells found.");
                return new Pair<>(false, null);
            }

            // 顶层 children -> builder 列表
            List<HeaderCellPB.Builder> topBuilders = new ArrayList<>();
            for (HeaderCellPB c : tablePb.getHeader().getTopLevelCellList()) {
                topBuilders.add(c.toBuilder());
            }

            // 顶层四个初值：top/left/remainingRows/remainingWidth
            float top    = ctx.tableTop;      // 表格顶边
            float left   = ctx.tableLeft;     // 表格左边
            int   rows   = ctx.headerRows;    // 顶层可用表头行数
            float width  = ctx.tableWidth;    // 顶层可分配宽度

            // 布局顶层
            List<String> colParamCodes = new ArrayList<>();
            if (!layoutCells(ctx, null, topBuilders, top, left, rows, width, colParamCodes)) {
                return new Pair<>(false, null);
            }

            // 写回 header、表高、列元数据
            List<HeaderCellPB> topCells = new ArrayList<>();
            for (HeaderCellPB.Builder b : topBuilders) {
                topCells.add(b.build());
            }
            subPages.add(
                SubPagePB.newBuilder().setTable(TablePB.newBuilder()
                    .setHeader(TableHeaderPB.newBuilder().addAllTopLevelCell(topCells).build())
                    .addAllColParamCode(colParamCodes)
                    .build()
                )
                .setNotes(subPage.getNotes())
                .addAllTextElem(subPage.getTextElemList())
                .build()
            );
        }
        builder.setPage(PagePB.newBuilder()
            .setTblCommon(
                tblCommonPb.toBuilder()
                    .setHeight(ctx.tableHeight)
                    .addAllParamColMeta(ctx.paramColMetaList)
                    .build()
            ).addAllSubPage(subPages).build());

        return new Pair<>(true, builder.build());
    }

    private boolean layoutCells(
        TableCommonContext ctx,
        HeaderCellPB.Builder parentOrNull, List<HeaderCellPB.Builder> children,
        float top, float left, int remainingRows, float remainingWidth,
        List<String> colParamCodes
    ) {
        if (children == null || children.isEmpty()) {
            log.error("No children cells to layout.");
            return false;
        }
        if (remainingRows <= 0 || remainingWidth < EPS) {
            String parentStr = (parentOrNull == null) ? "(root)" : getCellString(parentOrNull);
            log.error("Invalid remaining rows " + remainingRows + " or width " + remainingWidth +
                " for parent: " + parentStr);
            return false;
        }

        float cursorLeft = left;
        float widthLeft  = remainingWidth;

        for (int i = 0; i < children.size(); i++) {
            HeaderCellPB.Builder cell = children.get(i);

            // 1) 计算宽度：最后一个兄弟可用剩余宽度自动补齐
            boolean isLast = (i == children.size() - 1);
            float w = cell.getWidth();
            if (isLast) {
                // 吃满剩余宽度，并吸收轻微负误差
                float fill = widthLeft;
                if (fill < 0 && fill > -EPS) fill = 0f;
                if (fill <= 0f) {
                    log.error("Invalid widthLeft " + widthLeft + " for last cell: " + getCellString(cell));
                    return false;
                }
                w = fill;
                cell.setWidth(w);
            } else if (w < EPS || w - widthLeft > EPS) {
                log.error("Invalid width " + w + " for cell: " + getCellString(cell));
                return false;
            }

            // 2) 计算高度行数：叶子默认填满剩余行，非叶默认 1 行
            boolean leaf = (cell.getChildrenCount() == 0);
            int heightRows = cell.getHeightRows();
            if (leaf) {
                heightRows = remainingRows;
                cell.setHeightRows(heightRows);
            } else if (heightRows <= 0 || heightRows >= remainingRows) {
                log.error("Invalid height rows " + heightRows +
                    " remaining rows " + remainingRows + " for cell: " + getCellString(cell)
                );
                return false; // 非叶子没有高度行数 => 非法
            }

            // 3) 写衍生坐标
            float h = heightRows * ctx.rowHeight;
            float bottom = top - h;
            cell.setLeft(cursorLeft);
            cell.setBottom(bottom);
            cell.setHeight(h);

            // 4) 叶子：校验 param_code，并记录列元数据
            if (leaf) {
                String param = cell.getParamCode();
                if (param == null || param.isEmpty()) {
                    log.error("Invalid param_code for cell: " + getCellString(cell));
                    return false;
                }

                ctx.paramColMetaList.add(ParamColMetaPB.newBuilder()
                    .setParamCode(param).setLeft(cursorLeft).setWidth(w)
                    .build()
                );
                colParamCodes.add(param);

                // 推进到下一个兄弟
                cursorLeft += w;
                widthLeft  -= w;
                if (widthLeft < 0 && widthLeft > -EPS) widthLeft = 0f;
                continue;
            }

            // 5) 非叶：递归其 children
            int childCnt = cell.getChildrenCount();
            if (childCnt <= 0) return false;

            // 子层的可用行数/宽度
            int  childRemainingRows  = remainingRows - heightRows;
            float childRemainingW    = w;
            float childTop           = bottom;    // 子层从父 cell 底边往下布局
            float childLeft          = cursorLeft;

            // 准备子列表（Builder 列表）
            List<HeaderCellPB.Builder> childBuilders = new ArrayList<>(childCnt);
            for (int k = 0; k < childCnt; k++) {
                childBuilders.add(cell.getChildren(k).toBuilder());
            }

            if (!layoutCells(ctx, cell, childBuilders, childTop, childLeft, childRemainingRows, childRemainingW, colParamCodes)) {
                return false;
            }

            // 将规范化后的 children 写回该 cell
            cell.clearChildren();
            for (HeaderCellPB.Builder cb : childBuilders) {
                cell.addChildren(cb.build());
            }

            // 推进到下一个兄弟
            cursorLeft += w;
            widthLeft  -= w;
            if (widthLeft < 0 && widthLeft > -EPS) widthLeft = 0f;
        }

        return true;
    }

    private String getCellString(HeaderCellPB.Builder cell) {
        if (cell == null) return "null";

        StringBuilder sb = new StringBuilder();
        for (String content : cell.getContentList()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(content);
        }
        return sb.toString() + "(" + cell.getContentCode().ordinal() + ")";
    }

    private void drawLogicalPage(Ah2PdfContext ctx, PagePB pagePb) {
        if (ctx.document == null || ctx.font == null || ctx.pageData == null) {
            log.error("Invalid context or page data for drawing.");
            return;
        }

        for (SubPagePB subPage : pagePb.getSubPageList()) {
            drawPhysicalPage(ctx, subPage);
        }

        return;
    }

    private void drawPhysicalPage(Ah2PdfContext ctx, SubPagePB subPage) {
        if (ctx.document == null || ctx.font == null || subPage == null || ctx.pageData == null) {
            log.error("Invalid context or subpage data for drawing.");
            return;
        }
        ctx.table = subPage.getTable();
        ctx.pdPage = new PDPage(new PDRectangle(PDRectangle.A3.getHeight(), PDRectangle.A3.getWidth()));
        ctx.document.addPage(ctx.pdPage);

        // 获取病人数据
        LocalDateTime shiftTimeStart = ctx.pageData.pageEndTs;
        if (shiftTimeStart == null) {
            shiftTimeStart = ctx.pageData.pageStartTs;
        }
        if (shiftTimeStart == null) {
            shiftTimeStart = TimeUtils.getNowUtc();
        }
        Pair<ReturnCode, JfkDataService.JfkPatientInfo> patientInfoPair =
            jfkDataService.getJfkPatientInfo(ctx.pid, shiftTimeStart);
        JfkDataService.JfkPatientInfo patientInfo = patientInfoPair.getFirst().getCode() == 0 ?
            patientInfoPair.getSecond() : null;
        if (patientInfo == null) {
            log.error("Failed to get patient info for pid {}; shiftTimeStart {}", ctx.pid, shiftTimeStart);
            return;
        }

        // 画内容
        try (PDPageContentStream contentStream = new PDPageContentStream(ctx.document, ctx.pdPage)) {  // try-with-resources 会在退出作用域时自动调用 close()
            ctx.contentStream = contentStream;

            // 画表头
            setLineStyle(contentStream, ctx.tblLineStyle);
            TableCommonPB tblCommonPb = this.template.getPage().getTblCommon();
            contentStream.addRect(tblCommonPb.getLeft(), tblCommonPb.getBottom(), tblCommonPb.getWidth(), tblCommonPb.getHeight());
            float tableHeaderBottom = tblCommonPb.getBottom() + tblCommonPb.getHeight() - tblCommonPb.getRowHeight() * tblCommonPb.getHeaderRows();
            ctx.tableHeaderBottom = tableHeaderBottom;
            contentStream.moveTo(tblCommonPb.getLeft(), tableHeaderBottom);
            contentStream.lineTo(tblCommonPb.getLeft() + tblCommonPb.getWidth(), tableHeaderBottom);
            contentStream.stroke();

            for (int i = 0; i < ctx.table.getHeader().getTopLevelCellCount(); i++) {
                HeaderCellPB cell = ctx.table.getHeader().getTopLevelCell(i);
                boolean isLastSibling = (i == ctx.table.getHeader().getTopLevelCellCount() - 1);
                drawTableHeaderCell(ctx, cell, isLastSibling);
            }

            // 画两侧序号
            for (int i = 0; i < tblCommonPb.getBodyRows(); ++i) {
                String orderStr = String.valueOf(tblCommonPb.getBodyRows() - i);
                float orderWidth = JfkPdfUtils.textWidth(
                    ctx.font, ctx.tblTxtStyle.getFontSize(),
                    orderStr, ctx.tblTxtStyle.getCharSpacing()
                );

                float bottom = tblCommonPb.getBottom() + i * tblCommonPb.getRowHeight()
                    + (tblCommonPb.getRowHeight() - ctx.tblTxtStyle.getFontSize()) / 2f;
                PdfTextRenderer.drawTxt(
                    ctx.contentStream,
                    ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK, ctx.tblTxtStyle.getCharSpacing(),
                    tblCommonPb.getLeft() - orderWidth - ctx.tblTxtStyle.getCharSpacing() * 2, bottom,
                    orderStr);
                PdfTextRenderer.drawTxt(
                    ctx.contentStream,
                    ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK, ctx.tblTxtStyle.getCharSpacing(),
                    tblCommonPb.getLeft() + tblCommonPb.getWidth() + ctx.tblTxtStyle.getCharSpacing() * 2, bottom,
                    orderStr);
            }

            // 画表体
            ah2ReportData.drawTableBody(ctx);

            // 画备注
            if (!StrUtils.isBlank(subPage.getNotes())) {
                float notesBottom = tblCommonPb.getBottom() - ctx.tblTxtStyle.getFontSize() * 1.2f;
                PdfTextRenderer.drawTxt(
                    ctx.contentStream,
                    ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK, ctx.tblTxtStyle.getCharSpacing(),
                    tblCommonPb.getLeft(), notesBottom,
                    subPage.getNotes()
                );
            }

            // 画文本元素
            for (JfkTextPB textPb : subPage.getTextElemList()) {
                String textContent = getTextContent(patientInfo, ctx.pageData.pageNumber, textPb);
                textPb = textPb.toBuilder().setContent(textContent).build();
                JfkPdfUtils.drawJfkText(ctx.contentStream, ctx.font, textPb);
            }
        } catch (IOException e) {
            log.error("Error drawing subpage: " + e.getMessage());
        }
    }

    private void setLineStyle(PDPageContentStream stream, LineStylePB style) throws IOException {
        stream.setLineWidth(style.getWidth());
        stream.setStrokingColor(style.getGrayValue());

        float[] dashPattern;
        if (style.getDashPatternCount() == 0) {
            dashPattern = new float[0];
        } else {
            dashPattern = new float[2];
            dashPattern[0] = style.getDashPattern(0);
            dashPattern[1] = style.getDashPattern(1);
        }
        stream.setLineDashPattern(dashPattern, 0/*虚线的起始偏移量*/);
    }

    private void drawTableHeaderCell(
        Ah2PdfContext ctx, HeaderCellPB cell, boolean isLastSibling // 是否为本层最右边的兄弟
    ) throws IOException {
        PDPageContentStream cs = ctx.contentStream;

        // 1) 画本cell的 right edge（最右边的兄弟不画，避免与外框右边界重复）
        final float top = cell.getBottom() + cell.getHeight();
        final float right = cell.getLeft() + cell.getWidth();
        if (!isLastSibling) {
            cs.moveTo(right, top);
            cs.lineTo(right, ctx.tableHeaderBottom);
            cs.stroke();
        }

        // 2) 画本cell的 bottom edge（仅非叶子）
        if (cell.getChildrenCount() > 0) {
            cs.moveTo(cell.getLeft(), cell.getBottom());
            cs.lineTo(right, cell.getBottom());
            cs.stroke();
        }

        // 3) 画cell的文本
        List<String> contentList = new ArrayList<>();
        if (cell.getContentCode() != ContentCodeEnum.NA) {
            String content = ah2ReportData.getContent(cell.getContentCode(), ctx.pageData);
            if (content != null && !content.isEmpty()) {
                contentList.add(content);
            }
        }
        contentList.addAll(cell.getContentList());
        List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
            ctx.font, ctx.tblTxtStyle.getFontSize(),
            cell.getWidth() - 2 * ctx.tblTxtStyle.getPadding(),
            ctx.tblTxtStyle.getCharSpacing(), contentList
        );
        PdfTextRenderer.drawTxt(
            ctx.contentStream,
            ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
            ctx.tblTxtStyle.getCharSpacing(), ctx.tblTxtStyle.getFontSize() * 1.2f, HorizontalAlign.CENTER,
            cell.getLeft(), cell.getBottom(), cell.getWidth(), cell.getHeight(),
            wrappedLines
        );

        int childCount  = cell.getChildrenCount();
        for (int i = 0; i < childCount; i++) {
            HeaderCellPB child = cell.getChildren(i);
            boolean childIsLast = (i == childCount - 1);
            drawTableHeaderCell(ctx, child, childIsLast);
        }
    }

    private String getTextContent(JfkDataService.JfkPatientInfo jfkPatInfo, int pageNumber, JfkTextPB textPb) {
        if (textPb.getId().equals("ah2:page_number")) {
            return "第 " + pageNumber + " 页";
        }
        if (jfkPatInfo != null) {
            if (textPb.getId().equals("patient_info:bed_no")) {
                return jfkPatInfo.bedNumber;
            }
            if (textPb.getId().equals("patient_info:patient_name")) {
                return jfkPatInfo.name;
            }
            if (textPb.getId().equals("patient_info:mrn")) {
                return jfkPatInfo.mrn;
            }
            if (textPb.getId().equals("patient_info:age")) {
                return jfkPatInfo.ageStr;
            }
            if (textPb.getId().equals("patient_info:diagnosis")) {
                return jfkPatInfo.diagnosis;
            }
        }

        return textPb.getContent();  // 目前仅支持静态文本
    }

    private static final float EPS = 0.1f;

    private final List<String> statusCodeMsgs;
    private JfkDataService jfkDataService;

    private byte[] fontDataBytes;
    private final ReportTemplateAh2PB template;
    private final Ah2ReportData ah2ReportData;
}
