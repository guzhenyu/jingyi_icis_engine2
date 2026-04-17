package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkElementMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTableColumnMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;

public class JfkTableRendererTests {
    @Test
    public void buildElasticRowsUsesExplicitStringsLineCount() throws Exception {
        JfkTableRenderer renderer = new JfkTableRenderer(new JfkTextRenderer());
        JfkValueResolver resolver = new JfkValueResolver(new JfkRenderData(
            List.of(dataSourceMeta()),
            List.of(dataSource("col_a", stringsVal("a1", "a2"), stringsVal("b1")))
        ));

        List<JfkTableRenderer.RowData> rows = renderer.buildElasticRows(table(), resolver);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).height()).isEqualTo(19f);
        assertThat(rows.get(1).height()).isEqualTo(12f);
    }

    @Test
    public void buildFixedRowsUsesColumnAlignmentWhenConfigured() throws Exception {
        JfkTableRenderer renderer = new JfkTableRenderer(new JfkTextRenderer());
        JfkValueResolver resolver = new JfkValueResolver(new JfkRenderData(List.of(), List.of()));
        JfkTablePB table = JfkTablePB.newBuilder()
            .setId("table-1")
            .setType("table")
            .setRows(1)
            .setCols(2)
            .addCellWidths(50)
            .addCellWidths(50)
            .addCellHeights(12)
            .setVAlignId(2)
            .setHAlignId(5)
            .addColumnMetas(column("col_a").toBuilder()
                .setVAlignId(3)
                .setHAlignId(6)
                .build())
            .addColumnMetas(column("col_b"))
            .build();

        List<JfkTableRenderer.RowData> rows = renderer.buildFixedRows(table, resolver);

        assertThat(rows.get(0).cells().get(0).vAlignId()).isEqualTo(3);
        assertThat(rows.get(0).cells().get(0).hAlignId()).isEqualTo(6);
        assertThat(rows.get(0).cells().get(1).vAlignId()).isEqualTo(2);
        assertThat(rows.get(0).cells().get(1).hAlignId()).isEqualTo(5);
    }

    @Test
    public void buildElasticRowsRejectsMismatchedDataColumnLengths() {
        JfkTableRenderer renderer = new JfkTableRenderer(new JfkTextRenderer());
        JfkValueResolver resolver = new JfkValueResolver(new JfkRenderData(
            List.of(dataSourceMeta()),
            List.of(dataSource("col_a", stringsVal("a1"), stringsVal("b1")))
        ));
        JfkDataSourcePB mismatch = resolverDataSourceWithMismatch();
        resolver = new JfkValueResolver(new JfkRenderData(List.of(dataSourceMeta()), List.of(mismatch)));

        JfkValueResolver finalResolver = resolver;
        assertThatThrownBy(() -> renderer.buildElasticRows(table(), finalResolver))
            .isInstanceOf(JfkRenderException.class)
            .hasMessageContaining("table_id=table-1")
            .hasMessageContaining("col_a:2")
            .hasMessageContaining("col_b:1");
    }

    private JfkTablePB table() {
        return JfkTablePB.newBuilder()
            .setId("table-1")
            .setType("table")
            .setRows(1)
            .setCols(2)
            .addCellWidths(50)
            .addCellWidths(50)
            .addCellHeights(12)
            .setFontSize(6)
            .setDataSourceMetaId("ds")
            .addColumnMetas(column("col_a"))
            .addColumnMetas(column("col_b"))
            .build();
    }

    private JfkTableColumnMetaPB column(String fieldId) {
        return JfkTableColumnMetaPB.newBuilder()
            .setId("meta-" + fieldId)
            .setName(fieldId)
            .setElementMeta(JfkElementMetaPB.newBuilder()
                .setContentType(4)
                .setDataSourceMetaId("ds")
                .setDataSourceFieldId(fieldId)
                .build())
            .build();
    }

    private JfkDataSourceMetaPB dataSourceMeta() {
        return JfkDataSourceMetaPB.newBuilder()
            .setId("ds")
            .addOutputFields(fieldMeta("col_a", 9))
            .addOutputFields(fieldMeta("col_b", 4))
            .build();
    }

    private JfkFieldMetaPB fieldMeta(String id, int valType) {
        return JfkFieldMetaPB.newBuilder()
            .setId(id)
            .setValMeta(JfkValMetaPB.newBuilder().setValType(valType).build())
            .build();
    }

    private JfkDataSourcePB dataSource(String colAId, JfkValPB first, JfkValPB second) {
        return JfkDataSourcePB.newBuilder()
            .setId("ds-1")
            .setMetaId("ds")
            .addOutputData(JfkFieldDataPB.newBuilder()
                .setId(colAId)
                .addVals(first)
                .addVals(second)
                .build())
            .addOutputData(JfkFieldDataPB.newBuilder()
                .setId("col_b")
                .addVals(JfkValPB.newBuilder().setStrVal("x").build())
                .addVals(JfkValPB.newBuilder().setStrVal("y").build())
                .build())
            .build();
    }

    private JfkDataSourcePB resolverDataSourceWithMismatch() {
        return JfkDataSourcePB.newBuilder()
            .setId("ds-1")
            .setMetaId("ds")
            .addOutputData(JfkFieldDataPB.newBuilder()
                .setId("col_a")
                .addVals(stringsVal("a1"))
                .addVals(stringsVal("a2"))
                .build())
            .addOutputData(JfkFieldDataPB.newBuilder()
                .setId("col_b")
                .addVals(JfkValPB.newBuilder().setStrVal("x").build())
                .build())
            .build();
    }

    private JfkValPB stringsVal(String... lines) {
        return JfkValPB.newBuilder().addAllStrsVal(List.of(lines)).build();
    }
}
