package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "monitoring_params_history")
public class MonitoringParamHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id; // 主键

    @Column(name = "code", nullable = false)
    private String code; // 观察参数编码（含出入量）

    @Column(name = "dept_id")
    private String deptId; // 科室ID

    @Column(name = "name")
    private String name; // 观察参数名称，比如“静脉入量”，“尿量”等

    @Column(name = "type_pb", length = 2000)
    private String typePb; // 将ValueMeta序列化并用base64编码的字符串

    @Column(name = "balance_type")
    private Integer balanceType; // 1: 入量；2: 出量；其他不计入

    @Column(name = "ui_type")
    private Integer uiType;  // UI 类型

    @Column(name = "is_deleted",  nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy; // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 最后修改时间
}
