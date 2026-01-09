package com.jingyicare.jingyi_icis_engine.entity.overviews;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "overview_params", indexes = {
    @Index(name = "idx_overview_params_name", columnList = "group_id, param_name")
})
public class OverviewParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 自增主键

    @Column(name = "group_id", nullable = false)
    private Long groupId; // 所属分组ID，外键 overview_groups.id

    @Column(name = "param_name", nullable = false)
    private String paramName; // 参数名称

    @Column(name = "graph_type", nullable = false)
    private Integer graphType; // 图表类型：如柱状图、折线图

    @Column(name = "color")
    private String color; // 图表颜色

    @Column(name = "point_icon")
    private String pointIcon; // 点图标样式(ECharts点定义)

    @Column(name = "param_type", nullable = false)
    private Integer paramType; // 参数类型：1-血气，2-检验，3-观察项

    @Column(name = "bga_category_id")
    private Integer bgaCategoryId; // 血气类别ID，仅当param_type为1时有效

    @Column(name = "param_code", nullable = false)
    private String paramCode; // 参数编码

    @Column(name = "value_meta")
    private String valueMeta; // proto消息ValueMetaPB实例序列化后的base64编码

    @Column(name = "balance_type_id", nullable = false)
    private Integer balanceTypeId; // 平衡类型ID： 0-不统计平衡，1-入量，2-出量，3-平衡 

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder; // 参数显示顺序

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人账号

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy; // 最后修改人账号

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName; // 最后修改人姓名

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 最后修改时间
}