package com.jingyicare.jingyi_icis_engine.service.reports.ah2report;

import java.awt.Color;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Base64;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisReport.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportAh2.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

import static com.jingyicare.jingyi_icis_engine.service.reports.ah2report.Ah2ReportCodes.*;

@Component
@Slf4j
public class Ah2TableRenderer {
    public Ah2TableRenderer(@Autowired ConfigProtoService protoService) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
    }

    public String getContent(ContentCodeEnum contentCode, Ah2PageData pageData) {
        if (contentCode == ContentCodeEnum.YEAR) {
            return pageData.yearStr == null ? "" : pageData.yearStr;
        }
        return "";
    }

    public float calcCellTextWidthPt(PDFont font, float fontSize, float charSpacing, String str) {
        if (font == null || fontSize <= 0f) return 0f;
        try {
            return JfkPdfUtils.textWidth(font, fontSize, str == null ? "" : str, charSpacing);
        } catch (IOException e) {
            log.error("Failed to calc cell text width for [{}]: {}", str, e.getMessage(), e);
            return 0f;
        }
    }

    public void drawTableBody(Ah2PdfContext ctx) throws IOException {
        // 如果需要填写时间列，则画时间竖线
        float summaryColLeft = ctx.tblCommon.getLeft();
        float summaryColWidth = ctx.tblCommon.getWidth();
        boolean hasDateTimeCols = ctx.table.getColParamCodeList().contains(AH2P_HHMM) ||
            ctx.table.getColParamCodeList().contains(AH2P_MMDD);
        boolean hasSignatureCol = ctx.table.getColParamCodeList().contains(AH2P_SIGNATURE);
        if (hasDateTimeCols) {
            ParamColMetaPB hhMMCol = ctx.colMetaMap.get(AH2P_HHMM);
            if (hhMMCol != null) {
                float x = hhMMCol.getLeft() + hhMMCol.getWidth();
                ctx.contentStream.moveTo(x, ctx.tableHeaderBottom);
                ctx.contentStream.lineTo(x, ctx.tblCommon.getBottom());
                ctx.contentStream.stroke();
                summaryColLeft += hhMMCol.getWidth();
                summaryColWidth -= hhMMCol.getWidth();
            }
            ParamColMetaPB mmDDCol = ctx.colMetaMap.get(AH2P_MMDD);
            if (mmDDCol != null) {
                float x = mmDDCol.getLeft() + mmDDCol.getWidth();
                ctx.contentStream.moveTo(x, ctx.tableHeaderBottom);
                ctx.contentStream.lineTo(x, ctx.tblCommon.getBottom());
                ctx.contentStream.stroke();
                summaryColLeft += mmDDCol.getWidth();
                summaryColWidth -= mmDDCol.getWidth();
            }
            ParamColMetaPB signCol = hasSignatureCol ? ctx.colMetaMap.get(AH2P_SIGNATURE) : null;
            if (signCol != null) {
                summaryColWidth -= signCol.getWidth();
            }
        }

// log.info("\n\npageData:\n{}\n\n", ProtoUtils.protoToTxt(ctx.pageData.toProto()));
        // 画数据块
        for (Ah2PageData.RowBlock rowBlock : ctx.pageData.rowBlocks) {
            // - 画表体横线
            for (int i = 0; i < rowBlock.totalRows; i++) {
                int rowIdx = rowBlock.startRow + i;
                if (rowIdx == ctx.tblCommon.getBodyRows() - 1) break; // 最后一行不画
                float y = ctx.tableHeaderBottom - (rowIdx + 1) * ctx.tblCommon.getRowHeight();
                ctx.contentStream.moveTo(ctx.tblCommon.getLeft(), y);
                ctx.contentStream.lineTo(ctx.tblCommon.getLeft() + ctx.tblCommon.getWidth(), y);
                ctx.contentStream.stroke();
            }

            if (rowBlock.isSummaryRow) {  // 画汇总行文字
                if (hasDateTimeCols) {
                    // 日期时间
                    float dateBottom = ctx.tableHeaderBottom -
                        (rowBlock.startRow + 1) * ctx.tblCommon.getRowHeight();
                    LocalDateTime tsLocal = TimeUtils.getLocalDateTimeFromUtc(rowBlock.timestamp, ZONE_ID);
                    List<String> mMddStr = tsLocal == null || !rowBlock.leadingDataBlock ? Collections.singletonList("") :
                        Collections.singletonList(isXiuning(ctx) ? TimeUtils.getYMDTimeString(tsLocal) : TimeUtils.getMonthDay(tsLocal));
                    ParamColMetaPB colMeta = ctx.colMetaMap.get(AH2P_MMDD);
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.CENTER,
                        colMeta.getLeft(), dateBottom, colMeta.getWidth(), 1 * ctx.tblCommon.getRowHeight(),
                        mMddStr
                    );
                    List<String> hHMMStr = tsLocal == null || !rowBlock.leadingDataBlock ? Collections.singletonList("") :
                        Collections.singletonList(TimeUtils.getHourMinute(tsLocal));
                    colMeta = ctx.colMetaMap.get(AH2P_HHMM);
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.CENTER,
                        colMeta.getLeft(), dateBottom, colMeta.getWidth(), 1 * ctx.tblCommon.getRowHeight(),
                        hHMMStr
                    );

                    // 汇总行文字
                    int summaryLines = Math.max(1, rowBlock.summary.size());
                    float summaryBottom = ctx.tableHeaderBottom -
                        (rowBlock.startRow + summaryLines) * ctx.tblCommon.getRowHeight();
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.LEFT,
                        summaryColLeft + ctx.tblCommon.getSummaryRowHotizontalPadding(),
                        summaryBottom, summaryColWidth, summaryLines * ctx.tblCommon.getRowHeight(),
                        rowBlock.summary
                    );

                    // 画汇总列和签名列之间的竖线
                    float x = summaryColLeft + summaryColWidth;
                    float y0 = summaryBottom;
                    float y1 = y0 + summaryLines * ctx.tblCommon.getRowHeight();
                    ctx.contentStream.moveTo(x, y0);
                    ctx.contentStream.lineTo(x, y1);
                    ctx.contentStream.stroke();

                    // 画签名列
                    ParamColMetaPB signColMeta = hasSignatureCol ? ctx.colMetaMap.get(AH2P_SIGNATURE) : null;
                    List<String> signatureLines = rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
                    if (signColMeta != null && signatureLines != null && !signatureLines.isEmpty()) {
                        drawSignatureImage(
                            ctx, signatureLines,
                            signColMeta.getLeft(), summaryBottom,
                            signColMeta.getWidth(), summaryLines * ctx.tblCommon.getRowHeight()
                        );
                    }

                    // 小计行上边框加粗；总计行上下边框加粗
                    final float ordinaryLineWidth = ctx.tblCommon.getLineStyle().getWidth();
                    final float ordinaryGray = ctx.tblCommon.getLineStyle().getGrayValue();
                    if (rowBlock.summary.size() == 1 &&
                        rowBlock.summary.get(0).startsWith("总计")
                    ) {
                        // 总计行
                        float yTop = ctx.tableHeaderBottom - rowBlock.startRow * ctx.tblCommon.getRowHeight();
// log.info("\n\n\n(总计) top = {}, bottom = {}\n\n\n", yTop, summaryBottom);
                        float yBottom = summaryBottom;
                        ctx.contentStream.setLineWidth(ordinaryLineWidth * 5);
                        ctx.contentStream.setStrokingColor(0f);
                        ctx.contentStream.moveTo(ctx.tblCommon.getLeft(), yTop);
                        ctx.contentStream.lineTo(ctx.tblCommon.getLeft() + ctx.tblCommon.getWidth(), yTop);
                        ctx.contentStream.stroke();
                        ctx.contentStream.moveTo(ctx.tblCommon.getLeft(), yBottom);
                        ctx.contentStream.lineTo(ctx.tblCommon.getLeft() + ctx.tblCommon.getWidth(), yBottom);
                        ctx.contentStream.stroke();
                        ctx.contentStream.setLineWidth(ordinaryLineWidth);
                        ctx.contentStream.setStrokingColor(ordinaryGray);
                    } else {
                        // 小计行
                        float yTop = ctx.tableHeaderBottom - rowBlock.startRow * ctx.tblCommon.getRowHeight();
// log.info("\n\n\n(小计) top = {}, bottom = {}\n\n\n", yTop, summaryBottom);
                        ctx.contentStream.setLineWidth(ordinaryLineWidth * 5);
                        ctx.contentStream.setStrokingColor(0f);
                        ctx.contentStream.moveTo(ctx.tblCommon.getLeft(), yTop);
                        ctx.contentStream.lineTo(ctx.tblCommon.getLeft() + ctx.tblCommon.getWidth(), yTop);
                        ctx.contentStream.stroke();
                        ctx.contentStream.setLineWidth(ordinaryLineWidth);
                        ctx.contentStream.setStrokingColor(ordinaryGray);
                    }
                }  // 每页中除了第一个子页，其余子叶不填汇总行内容
            } else {  // 画普通行
                LocalDateTime tsLocal = rowBlock.timestamp == null ?
                    null : TimeUtils.getLocalDateTimeFromUtc(rowBlock.timestamp, ZONE_ID);
                for (int colIdx = 0; colIdx < ctx.table.getColParamCodeCount(); colIdx++) {
                    String paramCode = ctx.table.getColParamCode(colIdx);
                    ParamColMetaPB colMeta = ctx.colMetaMap.get(paramCode);
                    if (colMeta == null) {
                        log.error("Ah2ReportService.drawTableBody: colMetaMap missing paramCode=" + paramCode);
                        continue;
                    }
                    if (!paramCode.equals(AH2P_HHMM) && !paramCode.equals(AH2P_MMDD)
                        && colIdx != ctx.table.getColParamCodeCount() - 1
                    ) {  // 画列竖线
                        float x = colMeta.getLeft() + colMeta.getWidth();
                        float y0 = ctx.tableHeaderBottom - rowBlock.startRow * ctx.tblCommon.getRowHeight();
                        float y1 = y0 - rowBlock.totalRows * ctx.tblCommon.getRowHeight();
                        ctx.contentStream.moveTo(x, y0);
                        ctx.contentStream.lineTo(x, y1);
                        ctx.contentStream.stroke();
                    }

                    if (paramCode.equals(AH2P_SIGNATURE)) {
                        List<String> signatureLines = rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
                        if (signatureLines != null && !signatureLines.isEmpty()) {
                            float cellBottom = ctx.tableHeaderBottom -
                                (rowBlock.startRow + rowBlock.totalRows) * ctx.tblCommon.getRowHeight();
                            drawSignatureImage(
                                ctx, signatureLines,
                                colMeta.getLeft(), cellBottom,
                                colMeta.getWidth(), rowBlock.totalRows * ctx.tblCommon.getRowHeight()
                            );
                        }
                        continue;
                    }

                    // 填写文字
                    List<String> wrappedLines = paramCode.equals(AH2P_HHMM) ? 
                        (tsLocal == null || !rowBlock.leadingDataBlock ? Collections.singletonList("") :
                         Collections.singletonList(TimeUtils.getHourMinute(tsLocal))
                        ) : paramCode.equals(AH2P_MMDD) ?
                        (tsLocal == null || !rowBlock.leadingDataBlock ? Collections.singletonList("") :
                         Collections.singletonList(isXiuning(ctx) ? TimeUtils.getYMDTimeString(tsLocal) : TimeUtils.getMonthDay(tsLocal))
                        ) :
                        rowBlock.wrappedLinesByParam.get(paramCode);
                    if (wrappedLines == null) continue;
                    int nLines = Math.max(1, wrappedLines.size());
                    float cellBottom = ctx.tableHeaderBottom -
                        (rowBlock.startRow + nLines) * ctx.tblCommon.getRowHeight();

                    HorizontalAlign hAlign = HorizontalAlign.CENTER;
                    if (paramCode.equals(AH2P_MED_EXEC) || paramCode.equals(AH2P_NASOGASTRIC) ||
                        paramCode.equals(AH2P_NURSING_RECORD)
                    ) {
                        hAlign = HorizontalAlign.LEFT;
                    }

                    float textLeft = colMeta.getLeft();
                    float textWidth = colMeta.getWidth();
                    float padding = Math.max(0f, ctx.tblTxtStyle.getPadding());
                    if (padding > 0f && colMeta.getWidth() > 2f * padding) {
                        textLeft = colMeta.getLeft() + padding;
                        textWidth = colMeta.getWidth() - 2f * padding;
                    }

                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), hAlign,
                        textLeft, cellBottom, textWidth, nLines * ctx.tblCommon.getRowHeight(),
                        wrappedLines
                    );
                }
            }
        }
    }

    private void drawSignatureImage(
        Ah2PdfContext ctx, List<String> signatureLines,
        float left, float bottom, float width, float height
    ) {
        if (ctx == null || signatureLines == null || signatureLines.isEmpty()) return;
        float adjustedLeft = left + 1;
        float adjustedWidth = Math.max(0, width - 2);
        float lineHeight = ctx.tblCommon.getRowHeight();

        for (int idx = 0; idx < signatureLines.size(); idx++) {
            String idStr = signatureLines.get(idx);
            if (StrUtils.isBlank(idStr)) continue;
            PDImageXObject img = getSignatureImage(ctx, idStr);
            if (img == null) continue;

            float imgW = img.getWidth();
            float imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) continue;
            float scale = Math.min(adjustedWidth / imgW, lineHeight / imgH);
            float drawW = imgW * scale;
            float drawH = imgH * scale;
            // 对齐到该行区域：从下往上计算当前行底部
            float lineBottom = bottom + (signatureLines.size() - idx - 1) * lineHeight;
            float drawX = adjustedLeft + (adjustedWidth - drawW) / 2;
            float drawY = lineBottom + (lineHeight - drawH) / 2;
            try {
                ctx.contentStream.drawImage(img, drawX, drawY, drawW, drawH);
            } catch (IOException e) {
                log.error("Failed to draw signature image for {}: {}", idStr, e.getMessage());
            }
        }
    }

    private PDImageXObject getSignatureImage(Ah2PdfContext ctx, String idStr) {
        if (ctx == null || StrUtils.isBlank(idStr)) return null;
        Long accountId = null;
        try {
            accountId = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (accountId == null) return null;
        if (ctx.signImageCache != null && ctx.signImageCache.containsKey(accountId)) {
            return ctx.signImageCache.get(accountId);
        }
        String dataUrl = ctx.accountSignPicMap == null ? null : ctx.accountSignPicMap.get(accountId);
        if (StrUtils.isBlank(dataUrl)) return null;
        int commaIdx = dataUrl.indexOf(',');
        String base64Str = commaIdx >= 0 ? dataUrl.substring(commaIdx + 1) : dataUrl;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Str);
            PDImageXObject img = PDImageXObject.createFromByteArray(ctx.document, bytes, "sign-" + accountId);
            if (ctx.signImageCache != null) ctx.signImageCache.put(accountId, img);
            return img;
        } catch (Exception e) {
            log.error("Failed to decode signature image for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    private boolean isXiuning(Ah2PdfContext ctx) {
        return ctx != null && ReportProperties.Ah2.VARIANT_XIUNING.equals(ctx.variant);
    }


    private final String ZONE_ID;
}
