package com.jingyicare.jingyi_icis_engine.entity.medications;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@Entity
@Builder
@AllArgsConstructor
@Table(name = "medications")
public class Medication {
    @Id
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "spec")
    private String spec;

    @Column(name = "dose")
    private Double dose;

    @Column(name = "dose_unit")
    private String doseUnit;

    public Integer getPackageCount() {
        return packageCount == null ? 0 : packageCount;
    }
    @Column(name = "package_count")
    private Integer packageCount;

    public String getPackageUnit() {
        return packageUnit == null ? "" : packageUnit;
    }
    @Column(name = "package_unit")
    private String packageUnit;

    public Boolean getShouldCalculateRate() {
        return shouldCalculateRate == null ? false : shouldCalculateRate;
    }
    @Column(name = "should_calculate_rate")
    private Boolean shouldCalculateRate;

    public Integer getType() {
        return type == null ? 0 : type;
    }
    @Column(name = "type")
    private Integer type;

    public String getCompany() {
        return company == null ? "" : company;
    }
    @Column(name = "company")
    private String company;

    public Double getPrice() {
        return price == null ? 0.0 : price;
    }
    @Column(name = "price")
    private Double price;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed;

    public String getConfirmedBy() {
        return confirmedBy == null ? "" : confirmedBy;
    }
    @Column(name = "confirmed_by")
    private String confirmedBy;
}