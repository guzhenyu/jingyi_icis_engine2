package com.jingyicare.jingyi_icis_engine.service.checklists;

import java.time.LocalDateTime;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisChecklist.*;
import com.jingyicare.jingyi_icis_engine.entity.checklists.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class ChecklistUtils {
    public static DeptChecklistGroup toEntity(DeptChecklistGroupPB proto) {
        if (proto == null) {
            return null;
        }

        DeptChecklistGroup entity = DeptChecklistGroup.builder()
            .deptId(proto.getDeptId())
            .groupName(proto.getGroupName())
            .comments(proto.getComments())
            .displayOrder(proto.getDisplayOrder())
            .isDeleted(false)
            .build();
        
        if (proto.getId() > 0) {
            entity.setId(proto.getId());
        }

        return entity;
    }

    public static DeptChecklistGroupPB toProto(DeptChecklistGroup entity) {
        if (entity == null) {
            return null;
        }
        DeptChecklistGroupPB.Builder builder = DeptChecklistGroupPB.newBuilder()
            .setId(entity.getId() != null ? entity.getId() : 0)
            .setDeptId(entity.getDeptId())
            .setGroupName(entity.getGroupName())
            .setComments(!StrUtils.isBlank(entity.getComments()) ? entity.getComments() : "")
            .setDisplayOrder(entity.getDisplayOrder() != null ? entity.getDisplayOrder() : 0);

        return builder.build();
    }

    public static DeptChecklistItem toEntity(DeptChecklistItemPB proto) {
        if (proto == null) {
            return null;
        }

        DeptChecklistItem entity = DeptChecklistItem.builder()
            .itemName(proto.getItemName())
            .isCritical(proto.getIsCritical())
            .comments(proto.getComments())
            .hasNote(proto.getHasNote())
            .defaultNote(proto.getDefaultNote())
            .displayOrder(proto.getDisplayOrder())
            .isDeleted(false)
            .build();

        if (proto.getId() > 0) {
            entity.setId(proto.getId());
        }

        return entity;
    }

    public static DeptChecklistItemPB toProto(DeptChecklistItem entity) {
        if (entity == null) {
            return null;
        }

        return DeptChecklistItemPB.newBuilder()
            .setId(entity.getId() != null ? entity.getId() : 0)
            .setItemName(entity.getItemName())
            .setIsCritical(entity.getIsCritical())
            .setComments(!StrUtils.isBlank(entity.getComments()) ? entity.getComments() : "")
            .setHasNote(entity.getHasNote())
            .setDefaultNote(!StrUtils.isBlank(entity.getDefaultNote()) ? entity.getDefaultNote() : "")
            .setDisplayOrder(entity.getDisplayOrder() != null ? entity.getDisplayOrder() : 0)
            .build();
    }

    public static PatientChecklistRecord toEntity(PatientChecklistRecordPB proto, String deptId) {
        if (proto == null) {
            return null;
        }

        PatientChecklistRecord entity = PatientChecklistRecord.builder()
            .pid(proto.getPid())
            .deptId(deptId)
            .createdBy(proto.getCreatedBy())
            .modifiedBy(proto.getModifiedBy())
            .reviewedBy(proto.getReviewedBy())
            .isDeleted(false)
            .build();

        if (proto.getId() > 0) {
            entity.setId(proto.getId());
        }

        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(proto.getEffectiveTimeIso8601(), "UTC");
        if (effectiveTime != null) {
            entity.setEffectiveTime(effectiveTime);
        }

        LocalDateTime createdAt = TimeUtils.fromIso8601String(proto.getCreatedAtIso8601(), "UTC");
        if (createdAt != null) {
            entity.setCreatedAt(createdAt);
        }

        LocalDateTime modifiedAt = TimeUtils.fromIso8601String(proto.getModifiedAtIso8601(), "UTC");
        if (modifiedAt != null) {
            entity.setModifiedAt(modifiedAt);
        }

        LocalDateTime reviewedAt = TimeUtils.fromIso8601String(proto.getReviewedAtIso8601(), "UTC");
        if (reviewedAt != null) {
            entity.setReviewedAt(reviewedAt);
        }

        return entity;
    }

    public static PatientChecklistRecordPB toProto(PatientChecklistRecord entity, String zoneId) {
        if (entity == null) {
            return null;
        }

        String effectiveTimeIso8601 = TimeUtils.toIso8601String(entity.getEffectiveTime(), zoneId);
        String createdAtIso8601 = TimeUtils.toIso8601String(entity.getCreatedAt(), zoneId);
        String modifiedAtIso8601 = TimeUtils.toIso8601String(entity.getModifiedAt(), zoneId);
        String reviewedAtIso8601 = TimeUtils.toIso8601String(entity.getReviewedAt(), zoneId);

        return PatientChecklistRecordPB.newBuilder()
            .setId(entity.getId() != null ? entity.getId() : 0)
            .setPid(entity.getPid())
            .setCreatedBy(entity.getCreatedBy())
            .setEffectiveTimeIso8601(effectiveTimeIso8601)
            .setCreatedAtIso8601(createdAtIso8601)
            .setModifiedBy(entity.getModifiedBy())
            .setModifiedAtIso8601(modifiedAtIso8601)
            .setReviewedBy(entity.getReviewedBy())
            .setReviewedAtIso8601(reviewedAtIso8601)
            .build();
    }

    public static PatientChecklistGroup toEntity(
        Long patientChecklistGroupId,
        Integer patientChecklistRecordId,
        Integer deptChecklistGroupId,
        Integer displayOrder
    ) {
        PatientChecklistGroup entity = PatientChecklistGroup.builder()
            .recordId(patientChecklistRecordId)
            .groupId(deptChecklistGroupId)
            .displayOrder(displayOrder)
            .isDeleted(false)
            .build();

        if (patientChecklistGroupId > 0) {
            entity.setId(patientChecklistGroupId);
        }

        return entity;
    }

    public static PatientChecklistGroupPB toProto(
        PatientChecklistGroup patientChecklistGroup,
        DeptChecklistGroup deptChecklistGroup
    ) {
        if (patientChecklistGroup == null || deptChecklistGroup == null) {
            return null;
        }

        Long patientChecklistGroupId = patientChecklistGroup.getId() != null ? patientChecklistGroup.getId() : 0;
        Integer displayOrder = patientChecklistGroup.getDisplayOrder() != null ? patientChecklistGroup.getDisplayOrder() : 0;

        return PatientChecklistGroupPB.newBuilder()
            .setId(patientChecklistGroupId)
            .setGroupName(deptChecklistGroup.getGroupName())
            .setComments(deptChecklistGroup.getComments())
            .setDisplayOrder(displayOrder)
            .build();
    }

    public static PatientChecklistItem toEntity(
        Long patientChecklistItemId,
        Long patientChecklistGroupId,
        Integer deptChecklistItemId,
        Boolean isChecked,
        String note,
        Integer displayOrder
    ) {
        PatientChecklistItem entity = PatientChecklistItem.builder()
            .groupId(patientChecklistGroupId)
            .itemId(deptChecklistItemId)
            .isChecked(isChecked)
            .note(note)
            .displayOrder(displayOrder)
            .isDeleted(false)
            .build();

        if (patientChecklistItemId > 0) {
            entity.setId(patientChecklistItemId);
        }

        return entity;
    }

    public static PatientChecklistItemPB toProto(
        PatientChecklistItem entity,
        DeptChecklistItem deptChecklistItem
    ) {
        if (deptChecklistItem == null || entity == null) {
            return null;
        }

        return PatientChecklistItemPB.newBuilder()
            .setId(entity.getId())
            .setItemName(deptChecklistItem.getItemName())
            .setIsCritical(deptChecklistItem.getIsCritical())
            .setComments(!StrUtils.isBlank(deptChecklistItem.getComments()) ? deptChecklistItem.getComments() : "")
            .setDisplayOrder(entity.getDisplayOrder() != null ? entity.getDisplayOrder() : 0)
            .setIsChecked(entity.getIsChecked())
            .setHasNote(deptChecklistItem.getHasNote())
            .setNote(entity.getNote())
            .build();
    }
}