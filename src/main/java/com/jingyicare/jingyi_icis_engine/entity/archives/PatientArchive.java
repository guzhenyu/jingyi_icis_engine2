package com.jingyicare.jingyi_icis_engine.entity.archives;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "patient_archives", indexes = {
    @Index(name = "idx_patient_archives_pid_type_local_midnight", columnList = "pid, type, local_midnight_utc")
})
public class PatientArchive {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "type", nullable = false)
    private Integer type;

    @Column(name = "local_midnight_utc", nullable = false)
    private LocalDateTime localMidnightUtc;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "data_pb", columnDefinition = "TEXT")
    private String dataPb;

    @Column(name = "relative_url")
    private String relativeUrl;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy;
}
