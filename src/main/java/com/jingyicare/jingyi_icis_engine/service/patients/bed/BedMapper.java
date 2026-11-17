package com.jingyicare.jingyi_icis_engine.service.patients.bed;

import java.time.LocalDateTime;
import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.BedCount;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedCountPB;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public final class BedMapper {
    private BedMapper() {}

    public static BedConfigPB toBedConfigPB(BedConfig bed, PatientRecord patient) {
        BedConfigPB.Builder builder = BedConfigPB.newBuilder()
            .setId(bed.getId())
            .setDepartmentId(bed.getDepartmentId())
            .setHisBedNumber(bed.getHisBedNumber())
            .setDeviceBedNumber(bed.getDeviceBedNumber())
            .setDisplayBedNumber(bed.getDisplayBedNumber())
            .setBedType(bed.getBedType())
            .setNote(Objects.toString(bed.getNote(), ""));
        if (patient != null) {
            builder.setPid(patient.getId())
                .setPatientName(patient.getIcuName())
                .setHisMrn(patient.getHisMrn());
        }
        return builder.build();
    }

    public static BedConfig toBedConfigEntity(BedConfigPB bed) {
        return BedConfig.builder()
            .id(bed.getId() > 0 ? bed.getId() : null)
            .departmentId(bed.getDepartmentId())
            .hisBedNumber(bed.getHisBedNumber())
            .deviceBedNumber(bed.getDeviceBedNumber())
            .displayBedNumber(bed.getDisplayBedNumber())
            .bedType(bed.getBedType())
            .note(bed.getNote())
            .isDeleted(false)
            .build();
    }

    public static BedCountPB toBedCountPB(BedCount count, String zoneId) {
        return BedCountPB.newBuilder()
            .setId(count.getId())
            .setDeptId(count.getDeptId())
            .setDateIso8601(TimeUtils.toIso8601String(count.getEffectiveTime(), zoneId))
            .setCount(count.getBedCount())
            .build();
    }

    public static BedCount toBedCountEntity(
        String deptId, BedCountPB count, LocalDateTime effectiveTimeUtc
    ) {
        return BedCount.builder()
            .deptId(deptId)
            .bedCount(count.getCount())
            .effectiveTime(effectiveTimeUtc)
            .isDeleted(false)
            .build();
    }
}
