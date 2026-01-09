package com.jingyicare.jingyi_icis_engine.service.reports;

import java.time.LocalDateTime;
import java.util.*;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class Ah2PageData {
    public static class RowBlock {
        public RowBlock() {
            this.isSummaryRow = false;
            this.summary = new ArrayList<>();
            this.wrappedLinesByParam = new HashMap<>();
            this.leadingDataBlock = true;
        }

        public LocalDateTime timestamp; // 可以为空

        public boolean isSummaryRow;  // 是否为汇总行
        // 仅 isSummaryRow == true 时有效
        public List<String> summary;
        // isSummaryRow == false 时有效
        public Map<String, List<String>> wrappedLinesByParam;

        // 该行从页面中的第几行开始（0-based），占据多少行
        public int startRow;
        public int totalRows;
        public boolean leadingDataBlock;  // 一个RowBlock被切分成多个RowBlock(分页)时，是否为第一个

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("RowBlock{\n");
            sb.append("  timestamp=").append(timestamp);
            sb.append(", isSummaryRow=").append(isSummaryRow).append("\n");
            if (isSummaryRow) {
                sb.append("  summary=\n");
                for (String s : summary) {
                    sb.append("    " + s).append("\n");
                }
            } else {
                sb.append("  wrappedLinesByParam=\n");
                for (Map.Entry<String, List<String>> entry : wrappedLinesByParam.entrySet()) {
                    sb.append("    " + entry.getKey() + ":\n");
                    for (String line : entry.getValue()) {
                        sb.append("      " + line).append("\n");
                    }
                }
            }
            sb.append("  startRow=").append(startRow).append("\n");
            sb.append("  totalRows=").append(totalRows).append("\n");
            sb.append("}");
            return sb.toString();
        }
    }

    public Ah2PageData() {
        this.rowBlocks = new ArrayList<>();
        this.yearStr = "";
        this.pageStartTs = null;
        this.pageEndTs = null;
    }

    public void fromProto(Ah2PageDataPB proto) {
        this.rowBlocks.clear();
        for (Ah2RowBlockPB rowBlockPB : proto.getRowBlocksList()) {
            RowBlock rowBlock = new RowBlock();
            rowBlock.timestamp = TimeUtils.fromIso8601String(rowBlockPB.getTsIso8601(), "UTC");
            rowBlock.isSummaryRow = rowBlockPB.getIsSummaryRow();
            rowBlock.summary.addAll(rowBlockPB.getSummaryList());
            for (Ah2CodeValPB codeValPB : rowBlockPB.getWrappedLinesByParamList()) {
                rowBlock.wrappedLinesByParam.put(codeValPB.getCode(),
                    new ArrayList<>(codeValPB.getValueList()));
            }

            this.rowBlocks.add(rowBlock);
        }
    }

    public Ah2PageDataPB toProto() {
        Ah2PageDataPB.Builder builder = Ah2PageDataPB.newBuilder();
        for (RowBlock rowBlock : this.rowBlocks) {
            Ah2RowBlockPB.Builder rowBlockBuilder = Ah2RowBlockPB.newBuilder();
            rowBlockBuilder.setTsIso8601(TimeUtils.toIso8601String(rowBlock.timestamp, "UTC"));
            rowBlockBuilder.setIsSummaryRow(rowBlock.isSummaryRow);
            rowBlockBuilder.addAllSummary(rowBlock.summary);

            for (Map.Entry<String, List<String>> entry : rowBlock.wrappedLinesByParam.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                Ah2CodeValPB.Builder codeValBuilder = Ah2CodeValPB.newBuilder();
                codeValBuilder.setCode(entry.getKey());
                codeValBuilder.addAllValue(entry.getValue());
                rowBlockBuilder.addWrappedLinesByParam(codeValBuilder.build());
            }
            builder.addRowBlocks(rowBlockBuilder.build());
        }

        return builder.build();
    }

    public int pageNumber;  // 从 1 递增
    public List<RowBlock> rowBlocks;  // 为当前页的所有片段，高度和满足 ≤ body_rows
    public String yearStr;
    public LocalDateTime pageStartTs;  // 可以为空
    public LocalDateTime pageEndTs;    // 可以为空
}