package com.jingyicare.jingyi_icis_engine.entity.patients;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "bed_counts",
    indexes = {
        @Index(name = "idx_bed_counts_deptid_effective_time", columnList = "deptId, effectiveTime")
    }
)
public class BedCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;  // 自增id

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 科室编码

    @Column(name = "bed_count", nullable = false)
    private Integer bedCount;  // 床位数量

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;  // 生效时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}