package com.jingyicare.jingyi_icis_engine.entity.tubes;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tube_types", indexes = {
    @Index(name = "idx_tube_types_dept_id_name", columnList = "dept_id, name")
})
public class TubeType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门代码

    @Column(name = "type", nullable = false)
    private String type;  // 管道类型名称

    @Column(name = "name", nullable = false)
    private String name;  // 管道展示名称

    @Column(name = "category")
    private String category;  // 管道分类

    @Column(name = "is_common")
    private Boolean isCommon;  // 是否为常用管道

    @Column(name = "is_disabled", nullable = false)
    private Boolean isDisabled;  // 是否已禁用

    @Column(name = "display_order")
    private Integer displayOrder;  // 管道展示顺序

    @Column(name = "is_deleted")
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}
