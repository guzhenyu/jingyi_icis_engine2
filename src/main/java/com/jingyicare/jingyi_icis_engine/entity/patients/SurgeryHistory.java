package com.jingyicare.jingyi_icis_engine.entity.patients;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "surgery_history", indexes = {
    @Index(name = "idx_surgery_history_patient_id", columnList = "patient_id")
})
public class SurgeryHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "code", length = 50)
    private String code;

    @Column(name = "surgeon_doctor_name", length = 255)
    private String surgeonDoctorName;

    @Column(name = "anesthesiologist_name", length = 255)
    private String anesthesiologistName;

    @Column(name = "operating_room_nurse_name", length = 255)
    private String operatingRoomNurseName;
}