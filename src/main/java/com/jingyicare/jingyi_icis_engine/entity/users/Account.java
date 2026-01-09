package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 自增主键

    @Column(name = "account_id", nullable = false)
    private String accountId; // 账号

    @Column(name = "name", nullable = false)
    private String name; // 姓名

    @Column(name = "gender")
    private Integer gender; // 性别

    @Column(name = "date_of_birth")
    private LocalDateTime dateOfBirth; // 生日

    @Column(name = "position")
    private String position; // 职位

    @Column(name = "title")
    private String title; // 职称

    @Column(name = "education_level")
    private String educationLevel; // 学历

    @Column(name = "marital_status")
    private Integer maritalStatus; // 婚姻状况，系统枚举值

    @Column(name = "phone")
    private String phone; // 手机号

    @Column(name = "start_date")
    private LocalDateTime startDate; // 入职日期

    @Column(name = "id_card_number")
    private String idCardNumber; // 身份证号

    @Column(name = "sign_pic")
    private String signPic; // 签名图片的base64编码

    @Column(name = "ca_id")
    private String caId; // CA证书ID

    @Column(name = "ca_sign_pic")
    private String caSignPic; // CA签名图片

    @Column(name = "ca_cert")
    private String caCert; // CA证书

    @Column(name = "is_disabled")
    private Integer isDisabled; // 是否被禁用

    @Column(name = "is_deleted", nullable = false, columnDefinition = "boolean default false")
    private Boolean isDeleted = false; // 是否已删除

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 修改时间

    @Column(name = "modified_by")
    private String modifiedBy; // 修改人
}