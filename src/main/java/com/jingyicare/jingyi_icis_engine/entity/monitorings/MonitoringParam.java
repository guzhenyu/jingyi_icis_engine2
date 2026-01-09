package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "monitoring_params")
public class MonitoringParam {

    @Id
    @Column(name = "code", nullable = false, unique = true)
    private String code; // 观察参数编码（含出入量）

    @Column(name = "name", nullable = false)
    private String name; // 观察参数名称，比如“静脉入量”，“尿量”等

    @Column(name = "name_en")
    private String nameEn; // 观察参数英文名称

    public String getNameEn() {
        if (nameEn == null) return "";
        return nameEn;
    }

    @Column(name = "type_pb", nullable = false, length = 2000)
    private String typePb; // 将ValueMeta序列化并用base64编码的字符串

    @Column(name = "balance_type", nullable = false)
    private Integer balanceType; // 1: 入量；2: 出量；其他不计入

    @Column(name = "category")
    private Integer category;  // MonitoringEnums.param_category_xx.id

    public Integer getCategory() {
        if (category == null) return 0;
        return category;
    }

    @Column(name = "ui_modal_code")
    private String uiModalCode;  // UI 类型

    @Column(name = "chart_sign")
    private String chartSign; // 图表标识

    public String getChartSign() {
        if (chartSign == null) return "";
        return chartSign;
    }

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder; // 序号
}