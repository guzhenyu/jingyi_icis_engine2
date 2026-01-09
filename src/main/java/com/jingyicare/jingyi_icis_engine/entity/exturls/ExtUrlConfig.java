package com.jingyicare.jingyi_icis_engine.entity.exturls;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "ext_url_configs", indexes = {
    @Index(name = "idx_ext_url_configs_dept_display_name", columnList = "dept_id, display_name")
})
public class ExtUrlConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId; // 科室ID

    @Column(name = "display_name", nullable = false)
    private String displayName; // 显示名称

    @Column(name = "pattern", length = 1000)
    private String pattern; // 匹配模式

    @Column(name = "ext_url_pb", columnDefinition = "TEXT")
    private String extUrlPb; // 外部URL配置pb数据

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt; // 最后修改时间

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy; // 最后修改人
}