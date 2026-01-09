package com.jingyicare.jingyi_icis_engine.entity.patients;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "device_data_hourly_approx",
    indexes = {
        @Index(name = "idx_device_data_hourly_approx_device_id", columnList = "deviceId"),
        @Index(name = "idx_device_data_hourly_approx_recorded_at", columnList = "recordedAt")
    }
)
public class DeviceDataHourlyApprox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 对应 BIGSERIAL

    @Column(name = "department_id")
    private String departmentId;  // 部门编码

    @Column(name = "device_id")
    private Integer deviceId;  // 设备id，外键 device_infos.device_id

    @Column(name = "device_type")
    private Integer deviceType;  // 设备类型

    @Column(name = "device_bed_number")
    private String deviceBedNumber;  // 设备床位号

    @Column(name = "param_code")
    private String paramCode;  // 监测参数code

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;  // 数据记录时间

    @Column(name = "recorded_str")
    private String recordedStr;  // 设备数据原始值

}