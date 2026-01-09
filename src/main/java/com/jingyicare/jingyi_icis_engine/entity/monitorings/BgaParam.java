package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "bga_params")
public class BgaParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                            // 自增ID

    @Column(name = "dept_id", nullable = false)
    private String deptId;                      // 科室ID

    @Column(name = "monitoring_param_code", nullable = false)
    private String monitoringParamCode;         // 监测参数编码

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;               // 显示顺序

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;            // 是否启用

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;          // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;
}
