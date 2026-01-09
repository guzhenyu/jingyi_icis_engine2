package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "bga_param_mappings",
       indexes = @Index(name = "idx_bga_param_mappings_dept_bga_lis",
                        columnList = "dept_id, bga_code, lis_result_code"))
public class BgaParamMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "bga_code", nullable = false)
    private String bgaCode;

    @Column(name = "lis_result_code")
    private String lisResultCode;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

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