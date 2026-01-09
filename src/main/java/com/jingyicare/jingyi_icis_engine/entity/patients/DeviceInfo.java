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
    name = "device_infos",
    indexes = {
        @Index(name = "idx_device_infos_device_name", columnList = "departmentId, deviceName")
    }
)
public class DeviceInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;  // 自增id

    @Column(name = "department_id", nullable = false)
    private String departmentId;  // 部门代码

    @Column(name = "device_sn")
    private String deviceSn;  // 设备序列号

    @Column(name = "device_bed_number")
    private String deviceBedNumber;  // 设备默认床号

    @Column(name = "device_type", nullable = false)
    private Integer deviceType;  // 设备类型 icis_device.proto:DeviceEnums.device_type

    @Column(name = "device_name", nullable = false)
    private String deviceName;  // 设备名称

    @Column(name = "device_ip")
    private String deviceIp;  // 设备的IP地址

    @Column(name = "device_port")
    private String devicePort;  // 设备端口

    @Column(name = "device_driver_code")
    private String deviceDriverCode;  // 驱动编码（内部编码）

    @Column(name = "network_protocol")
    private Integer networkProtocol;  // 网络协议（tcp/udp等），icis_device.proto:DeviceEnums.network_protocol

    @Column(name = "serial_protocol")
    private Integer serialProtocol;  // 串口端口（RS232, 485等），icis_device.proto:DeviceEnums.serial_protocol

    @Column(name = "model")
    private String model;  // 型号

    @Column(name = "manufacturer")
    private String manufacturer;  // 生产厂家

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 修改人姓名

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}