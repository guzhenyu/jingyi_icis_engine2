package com.jingyicare.jingyi_icis_engine.entity.doctors;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "disease_metas")  // 如果没有索引要求，可省略 indexes
public class DiseaseMeta {

    @Id
    @Column(name = "code", nullable = false)
    private String code; // 疾病编码

    @Column(name = "name", nullable = false)
    private String name; // 疾病名称

    @Column(name = "description", length = 1000)
    private String description; // 疾病描述

    @Lob
    @Column(name = "disease_meta_pbtxt", nullable = false, columnDefinition = "TEXT")
    private String diseaseMetaPbtxt; // 将DiseaseMetaPB序列化并base64后的字符串
}
