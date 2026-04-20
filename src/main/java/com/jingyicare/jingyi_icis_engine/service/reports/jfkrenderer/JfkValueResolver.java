package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import java.util.ArrayList;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkElementMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTableColumnMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;

public class JfkValueResolver {
    public JfkValueResolver(JfkRenderData data) {
        this.data = data;
    }

    public String resolveText(
        JfkTextPB text,
        int currentPageNo,
        int totalPages,
        String subPageId
    ) {
        JfkElementMetaPB meta = text.getElementMeta();
        int contentType = meta == null ? JfkRenderUtils.CONTENT_STATIC : meta.getContentType();
        return switch (contentType) {
            case JfkRenderUtils.CONTENT_JFK_DATA_SOURCE -> {
                JfkValPB val = resolveDataSourceVal(meta.getDataSourceMetaId(), meta.getDataSourceFieldId(), -1);
                JfkValMetaPB valMeta = resolveDataSourceValMeta(meta.getDataSourceMetaId(), meta.getDataSourceFieldId());
                yield valToDisplayString(val, valMeta);
            }
            case JfkRenderUtils.CONTENT_CUR_PAGE_ID -> String.valueOf(currentPageNo);
            case JfkRenderUtils.CONTENT_TOTAL_PAGES -> totalPages > 0 ? String.valueOf(totalPages) : "";
            case JfkRenderUtils.CONTENT_CUR_SUB_PAGE_ID -> subPageId == null ? "" : subPageId;
            case JfkRenderUtils.CONTENT_USER_INPUT, JfkRenderUtils.CONTENT_JFK_INPUT -> "";
            default -> text.getContent();
        };
    }

    public List<String> resolveCellLines(
        JfkTablePB table,
        JfkTableColumnMetaPB columnMeta,
        int rowIndex
    ) {
        return resolveCell(table, columnMeta, rowIndex).lines();
    }

    public ResolvedCell resolveCell(
        JfkTablePB table,
        JfkTableColumnMetaPB columnMeta,
        int rowIndex
    ) {
        JfkElementMetaPB meta = columnMeta.getElementMeta();
        int contentType = meta == null ? JfkRenderUtils.CONTENT_STATIC : meta.getContentType();
        if (contentType == JfkRenderUtils.CONTENT_STATIC) {
            String value = rowIndex < columnMeta.getStaticValsCount()
                ? columnMeta.getStaticVals(rowIndex)
                : "";
            return new ResolvedCell(null, null, List.of(value));
        }

        if (contentType != JfkRenderUtils.CONTENT_JFK_DATA_SOURCE) {
            return new ResolvedCell(null, null, List.of(""));
        }

        String metaId = dataSourceMetaId(table, meta);
        String fieldId = meta.getDataSourceFieldId();
        JfkValPB val = resolveDataSourceVal(metaId, table.getId(), fieldId, rowIndex);
        JfkValMetaPB valMeta = resolveDataSourceValMeta(metaId, fieldId);
        if (valMeta != null && valMeta.getValType() == JfkRenderUtils.VAL_TYPE_STRINGS) {
            if (val == null || val.getStrsValCount() == 0) {
                return new ResolvedCell(val, valMeta, List.of(""));
            }
            return new ResolvedCell(val, valMeta, new ArrayList<>(val.getStrsValList()));
        }
        return new ResolvedCell(val, valMeta, List.of(valToDisplayString(val, valMeta)));
    }

    public boolean shouldRenderTable(JfkTablePB table) {
        String metaId = table.getDataSourceMetaId();
        return !JfkDataSourceIds.isCompactTableScoped(metaId)
            || data.dataSourceForTable(metaId, table.getId()) != null;
    }

    public int dataSourceFieldLength(JfkTablePB table, JfkTableColumnMetaPB columnMeta) throws JfkRenderException {
        JfkElementMetaPB meta = columnMeta.getElementMeta();
        if (meta == null || meta.getContentType() != JfkRenderUtils.CONTENT_JFK_DATA_SOURCE) {
            return -1;
        }
        String metaId = dataSourceMetaId(table, meta);
        JfkFieldDataPB fieldData = data.outputFieldDataForTable(metaId, table.getId(), meta.getDataSourceFieldId());
        if (fieldData == null) {
            throw new JfkRenderException(
                "Data source field missing: table_id=" + table.getId()
                    + ", meta_id=" + metaId
                    + ", field_id=" + meta.getDataSourceFieldId()
            );
        }
        if (fieldData.getValsCount() > 0) return fieldData.getValsCount();
        return fieldData.hasVal() ? 1 : 0;
    }

    public String dataSourceFieldId(JfkTableColumnMetaPB columnMeta) {
        JfkElementMetaPB meta = columnMeta.getElementMeta();
        return meta == null ? "" : meta.getDataSourceFieldId();
    }

    private JfkValPB resolveDataSourceVal(String metaId, String fieldId, int rowIndex) {
        JfkFieldDataPB fieldData = data.outputFieldData(metaId, fieldId);
        return resolveDataSourceVal(fieldData, rowIndex);
    }

    private JfkValPB resolveDataSourceVal(String metaId, String tableId, String fieldId, int rowIndex) {
        JfkFieldDataPB fieldData = data.outputFieldDataForTable(metaId, tableId, fieldId);
        return resolveDataSourceVal(fieldData, rowIndex);
    }

    private JfkValPB resolveDataSourceVal(JfkFieldDataPB fieldData, int rowIndex) {
        if (fieldData == null) return null;
        if (rowIndex >= 0 && fieldData.getValsCount() > 0) {
            return rowIndex < fieldData.getValsCount() ? fieldData.getVals(rowIndex) : null;
        }
        if (fieldData.hasVal()) return fieldData.getVal();
        return fieldData.getValsCount() > 0 ? fieldData.getVals(0) : null;
    }

    private JfkValMetaPB resolveDataSourceValMeta(String metaId, String fieldId) {
        JfkFieldMetaPB fieldMeta = data.outputFieldMeta(metaId, fieldId);
        return fieldMeta == null || !fieldMeta.hasValMeta() ? null : fieldMeta.getValMeta();
    }

    private String dataSourceMetaId(JfkTablePB table, JfkElementMetaPB meta) {
        return meta.getDataSourceMetaId().isBlank()
            ? table.getDataSourceMetaId()
            : meta.getDataSourceMetaId();
    }

    private String valToDisplayString(JfkValPB val, JfkValMetaPB valMeta) {
        if (val == null) return "";
        int valType = valMeta == null ? 0 : valMeta.getValType();
        return switch (valType) {
            case JfkRenderUtils.VAL_TYPE_BOOL -> val.getBoolVal() ? "是" : "否";
            case JfkRenderUtils.VAL_TYPE_INT64 -> String.valueOf(val.getInt64Val());
            case JfkRenderUtils.VAL_TYPE_DOUBLE -> String.valueOf(val.getDoubleVal());
            case JfkRenderUtils.VAL_TYPE_DATETIME -> val.getStrVal();
            case JfkRenderUtils.VAL_TYPE_STRINGS -> val.getStrsValCount() == 0 ? "" : String.join("\n", val.getStrsValList());
            default -> val.getStrVal();
        };
    }

    public record ResolvedCell(JfkValPB val, JfkValMetaPB valMeta, List<String> lines) {
        public int valType() {
            return valMeta == null ? 0 : valMeta.getValType();
        }
    }

    private final JfkRenderData data;
}
