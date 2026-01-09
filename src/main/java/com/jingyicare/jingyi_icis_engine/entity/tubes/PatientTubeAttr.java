package com.jingyicare.jingyi_icis_engine.entity.tubes;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_tube_attrs", indexes = {
    @Index(name = "idx_pt_attrs_record_attr", columnList = "patient_tube_record_id, tube_attr_id")
})
public class PatientTubeAttr {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "patient_tube_record_id", nullable = false)
    private Long patientTubeRecordId;  // 病人插管记录ID

    @Column(name = "tube_attr_id", nullable = false)
    private Integer tubeAttrId;  // 管道属性ID

    @Column(name = "\"value\"", nullable = false)  // value是保留字
    private String value;  // 管道属性值
}
