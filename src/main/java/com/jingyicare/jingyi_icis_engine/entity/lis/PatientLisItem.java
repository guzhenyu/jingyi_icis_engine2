package com.jingyicare.jingyi_icis_engine.entity.lis;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_lis_items")
public class PatientLisItem {
    @Id
    @Column(name = "report_id")
    private String reportId;

    @Column(name = "mrn")
    private String mrn;

    @Column(name = "his_pid", nullable = false)
    private String hisPid;

    @Column(name = "lis_item_name")
    private String lisItemName;

    @Column(name = "lis_item_short_name")
    private String lisItemShortName;

    @Column(name = "lis_item_code")
    private String lisItemCode;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "order_dept")
    private String orderDept;

    @Column(name = "order_dept_id")
    private String orderDeptId;

    @Column(name = "order_doctor")
    private String orderDoctor;

    @Column(name = "order_doctor_id")
    private String orderDoctorId;

    @Column(name = "sample_id")
    private String sampleId;

    @Column(name = "sample_name")
    private String sampleName;

    @Column(name = "collect_time")
    private LocalDateTime collectTime;

    @Column(name = "receive_time")
    private LocalDateTime receiveTime;

    @Column(name = "auth_time")
    private LocalDateTime authTime;

    @Column(name = "auth_doctor")
    private String authDoctor;

    @Column(name = "status")
    private String status;
}