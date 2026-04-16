package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldMetaPB;

public class JfkRenderData {
    public JfkRenderData(
        List<JfkDataSourceMetaPB> dataSourceMetas,
        List<JfkDataSourcePB> dataSources
    ) {
        Map<String, JfkDataSourceMetaPB> metaMap = new LinkedHashMap<>();
        for (JfkDataSourceMetaPB meta : dataSourceMetas == null ? List.<JfkDataSourceMetaPB>of() : dataSourceMetas) {
            metaMap.put(meta.getId(), meta);
        }
        this.metaById = Collections.unmodifiableMap(metaMap);

        Map<String, JfkDataSourcePB> dsMap = new LinkedHashMap<>();
        for (JfkDataSourcePB dataSource : dataSources == null ? List.<JfkDataSourcePB>of() : dataSources) {
            dsMap.putIfAbsent(dataSource.getMetaId(), dataSource);
        }
        this.dataSourceByMetaId = Collections.unmodifiableMap(dsMap);
    }

    public JfkDataSourceMetaPB dataSourceMeta(String metaId) {
        return metaById.get(metaId);
    }

    public JfkDataSourcePB dataSource(String metaId) {
        return dataSourceByMetaId.get(metaId);
    }

    public JfkFieldMetaPB outputFieldMeta(String metaId, String fieldId) {
        JfkDataSourceMetaPB meta = dataSourceMeta(metaId);
        if (meta == null) return null;
        for (JfkFieldMetaPB fieldMeta : meta.getOutputFieldsList()) {
            if (fieldId.equals(fieldMeta.getId())) return fieldMeta;
        }
        return null;
    }

    public JfkFieldDataPB outputFieldData(String metaId, String fieldId) {
        JfkDataSourcePB dataSource = dataSource(metaId);
        if (dataSource == null) return null;
        for (JfkFieldDataPB fieldData : dataSource.getOutputDataList()) {
            if (fieldId.equals(fieldData.getId())) return fieldData;
        }
        return null;
    }

    private final Map<String, JfkDataSourceMetaPB> metaById;
    private final Map<String, JfkDataSourcePB> dataSourceByMetaId;
}
