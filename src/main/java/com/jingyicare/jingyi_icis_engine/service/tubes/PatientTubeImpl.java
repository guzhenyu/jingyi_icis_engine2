package com.jingyicare.jingyi_icis_engine.service.tubes;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;

import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class PatientTubeImpl {
    public PatientTubeImpl(
        @Autowired UserService userService,
        @Autowired TubeSetting tubeSetting,
        @Autowired TubeTypeRepository tubeTypeRepo,
        @Autowired TubeTypeAttributeRepository tubeTypeAttrRepo,
        @Autowired TubeTypeStatusRepository tubeTypeStatusRepo,
        @Autowired PatientTubeRecordRepository ptRecordRepo,
        @Autowired PatientTubeAttrRepository ptAttrRepo,
        @Autowired PatientTubeStatusRecordRepository ptStatusRecordRepo
    ) {
        this.DRAINAGE_TUBE_TYPE = tubeSetting.getDrainageTubeType();

        this.userService = userService;
        this.tubeSetting = tubeSetting;
        this.tubeTypeRepo = tubeTypeRepo;
        this.tubeTypeAttrRepo = tubeTypeAttrRepo;
        this.tubeTypeStatusRepo = tubeTypeStatusRepo;
        this.ptRecordRepo = ptRecordRepo;
        this.ptAttrRepo = ptAttrRepo;
        this.ptStatusRecordRepo = ptStatusRecordRepo;
    }

    /**
     * 根据请求从数据库中获取 PatientTubeRecord 数据
     * @param req 包含查询条件的请求对象
     * @return 查询结果列表
     */
    public List<PatientTubeRecord> fetchPatientTubeRecords(Long pid, boolean isDeleted) {
        return ptRecordRepo.findByPidAndIsDeleted(pid, isDeleted);
    }

    public List<PatientTubeRecord> fetchPatientTubeRecords(Long pid, String tubeName) {
        return ptRecordRepo.findByPidAndTubeNameAndIsDeletedFalseAndRemovedAtNull(pid, tubeName);
    }

    /**
     * 根据 PatientTubeRecord 列表获取其对应的属性记录
     * @param records 病人插管记录列表
     * @return 每个插管记录对应的属性列表映射
     */
    public Map<Long, List<TubeAttributePB>> fetchPatientTubeAttributes(List<PatientTubeRecord> records) {
        List<Long> recordIds = records.stream().map(PatientTubeRecord::getId).collect(Collectors.toList());
        List<PatientTubeAttr> attrs = ptAttrRepo.findByPatientTubeRecordIdIn(recordIds);

        // 获取插管记录对应的属性列表
        Map<Long, List<TubeAttributePB>> tubeAttrMap = attrs.stream().collect(
            Collectors.groupingBy(
                PatientTubeAttr::getPatientTubeRecordId,
                Collectors.mapping(attr -> TubeAttributePB.newBuilder()
                        .setTubeAttrId(attr.getTubeAttrId())
                        .setValue(attr.getValue())
                        .build(), 
                    Collectors.toList())
            ));

        // 根据属性的display order排序
        List<Integer> attributeIds = attrs.stream().map(PatientTubeAttr::getTubeAttrId).collect(Collectors.toList());
        Map<Integer, Integer> displayOrderMap = tubeTypeAttrRepo.findDisplayOrderByAttributeIds(attributeIds).stream()
            .collect(Collectors.toMap(AttributeDisplayOrder::getId, AttributeDisplayOrder::getDisplayOrder));

        return tubeAttrMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            attrListEntry -> attrListEntry.getValue().stream().sorted((a, b) -> {
                final int orderComparison = Integer.compare(
                    displayOrderMap.getOrDefault(a.getTubeAttrId(), Integer.MAX_VALUE),
                    displayOrderMap.getOrDefault(b.getTubeAttrId(), Integer.MAX_VALUE)
                );
                return orderComparison != 0 ? orderComparison : Long.compare(a.getTubeAttrId(), b.getTubeAttrId());
            }).collect(Collectors.toList())
        ));
    }

    @Transactional
    public Long addPatientTube(
        NewPatientTubeReq req, String tubeType, String tubeName,
        String accountId, String accountName
    ) {
        LocalDateTime insertedAt = TimeUtils.fromIso8601String(req.getInsertedAtIso8601(), "UTC");
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (insertedAt.isAfter(nowUtc)) {
            log.warn("inserted_at is reset since it was set to a future point, req: {}, accountid: {}",
                req, accountId);
            insertedAt = nowUtc;
        }

        LocalDateTime plannedRemovalAt = null;
        if (!StrUtils.isBlank(req.getPlannedRemovalAtIso8601())) {
            plannedRemovalAt = TimeUtils.fromIso8601String(req.getPlannedRemovalAtIso8601(), "UTC");
            if (!plannedRemovalAt.isAfter(insertedAt)) {
                log.warn("planed_removal_at is reset to null, since it is before the insersion point, req: {}, accountid: {}",
                    req, accountId);
                plannedRemovalAt = null;
            }
        }

        // 创建 PatientTubeRecord 实体
        PatientTubeRecord record = PatientTubeRecord.builder()
            .pid(req.getPid())
            .tubeTypeId(req.getTubeTypeId())
            .tubeName(tubeName)
            .insertedBy(accountId)
            .insertedByAccountName(accountName)
            .insertedAt(insertedAt)
            .plannedRemovalAt(plannedRemovalAt)
            .isDeleted(false)
            .note(req.getNote())
            .build();

        // 保存插管记录到数据库
        PatientTubeRecord savedRecord = ptRecordRepo.save(record);
        savedRecord.setRootTubeRecordId(savedRecord.getId());
        savedRecord = ptRecordRepo.save(savedRecord);

        // 处理属性列表
        if (req.getAttributesCount() > 0) {
            for (TubeAttributePB attribute : req.getAttributesList()) {
                PatientTubeAttr patientTubeAttr = PatientTubeAttr.builder()
                    .patientTubeRecordId(savedRecord.getId())
                    .tubeAttrId(attribute.getTubeAttrId())
                    .value(attribute.getValue())
                    .build();
                ptAttrRepo.save(patientTubeAttr);
            }
        }

        // 更新观察项
        tubeSetting.updateMonitoringParam(tubeType, tubeName);

        // 返回响应
        return savedRecord.getId();
    }

    @Transactional
    public void updatePatientTubeRecord(
        PatientTubeRecord record, String insertedBy, String insertedByAccountName,
        String insertedAtIso8601, String plannedRemovalAtIso8601, String removedAtIso8601,
        String note
    ) {
        if (!StrUtils.isBlank(insertedBy)) {
            record.setInsertedBy(insertedBy);
            record.setInsertedByAccountName(insertedByAccountName);
        }

        LocalDateTime insertedAt = TimeUtils.fromIso8601String(insertedAtIso8601, "UTC");
        if (insertedAt != null) record.setInsertedAt(insertedAt);

        LocalDateTime plannedRemovalAt = TimeUtils.fromIso8601String(plannedRemovalAtIso8601, "UTC");
        if (plannedRemovalAt != null) record.setPlannedRemovalAt(plannedRemovalAt);

        LocalDateTime removedAt = TimeUtils.fromIso8601String(removedAtIso8601, "UTC");
        if (removedAt != null) record.setRemovedAt(removedAt);

        if (!StrUtils.isBlank(note)) record.setNote(note);

        // 保存更新后的插管记录
        ptRecordRepo.save(record);
    }

    @Transactional
    public void removePatientTube(RemovePatientTubeReq req, String accountId) {
        // 获取待删除的插管记录
        Optional<PatientTubeRecord> recordOpt = ptRecordRepo.findById(req.getPatientTubeRecordId());
        if (recordOpt.isEmpty()) {
            log.warn("Tube record not found, id: {}", req.getPatientTubeRecordId());
            return;
        }

        PatientTubeRecord record = recordOpt.get();
        record.setIsUnplannedRemoval(req.getIsUnplannedRemoval() == 1);
        if (!StrUtils.isBlank(req.getRemovalReason())) record.setRemovalReason(req.getRemovalReason());

        String removedBy = StrUtils.isBlank(req.getRemovedBy()) ? accountId : req.getRemovedBy();
        String removedByAccountName = userService.getNameByAutoId(removedBy);

        record.setRemovedBy(removedBy);
        record.setRemovedByAccountName(removedByAccountName);

        final String removeAtIso8601 = req.getRemovedAtIso8601();
        record.setRemovedAt(StrUtils.isBlank(removeAtIso8601) ?
            TimeUtils.getNowUtc() :
            TimeUtils.fromIso8601String(removeAtIso8601, "UTC"));

        // 保存更新后的插管记录
        ptRecordRepo.save(record);

        log.info("Tube record removed successfully, id: {}, removedBy: {}, isUnplannedRemoval: {}, accountId: {}", 
                req.getPatientTubeRecordId(), record.getRemovedBy(), record.getIsUnplannedRemoval(), accountId);
        return;
    }

    @Transactional
    public void deletePatientTube(DeletePatientTubeReq req, String accountId) {
        // 获取待删除的插管记录
        Optional<PatientTubeRecord> recordOpt = ptRecordRepo.findById(req.getPatientTubeRecordId());
        if (recordOpt.isEmpty()) {
            log.warn("Tube record not found, id: {}", req.getPatientTubeRecordId());
            return;
        }

        PatientTubeRecord record = recordOpt.get();
        record.setIsDeleted(true);
        if (!StrUtils.isBlank(req.getDeleteReason())) record.setDeleteReason(req.getDeleteReason());
        record.setDeletedBy(accountId);
        record.setDeletedAt(TimeUtils.getNowUtc());

        // 保存更新后的插管记录
        ptRecordRepo.save(record);

        log.info("Tube record deleted successfully, id: {}, deletedBy: {}, deleteReason: {}, accountId: {}", 
                req.getPatientTubeRecordId(), record.getDeletedBy(), record.getDeleteReason(), accountId);
        return;
    }

    @Transactional
    public void retainTubeOnDischarge(RetainTubeOnDischargeReq req, String accountId) {
        // 获取待更新的插管记录
        Optional<PatientTubeRecord> recordOpt = ptRecordRepo.findById(req.getPatientTubeRecordId());
        if (recordOpt.isEmpty()) {
            log.warn("Tube record not found, id: {}", req.getPatientTubeRecordId());
            return;
        }

        PatientTubeRecord record = recordOpt.get();
        record.setIsRetainedOnDischarge(req.getIsRetain() == 1);

        // 保存更新后的插管记录
        ptRecordRepo.save(record);

        log.info("Tube record retain status updated successfully, id: {}, isRetainedOnDischarge: {}, accountId: {}", 
                req.getPatientTubeRecordId(), record.getIsRetainedOnDischarge(), accountId);
        return;
    }

    public PatientTubeRecord findPatientTubeRecord(Long recordId) {
        Optional<PatientTubeRecord> recordOpt = ptRecordRepo.findByIdAndIsDeletedFalse(recordId);
        return recordOpt.orElse(null);
    }

    @Transactional
    public Long replacePatientTube(ReplacePatientTubeReq req, PatientTubeRecord oldRecord, String accountId) {
        LocalDateTime removedAt = TimeUtils.fromIso8601String(req.getRemovedAtIso8601(), "UTC");
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (removedAt.isAfter(nowUtc)) {
            log.warn("removed_at is reset since it was set to a future point, req: {}, accountid: {}",
                req, accountId);
            removedAt = nowUtc;
        }

        LocalDateTime plannedRemovalAt = null;
        if (!StrUtils.isBlank(req.getPlannedRemovalAtIso8601())) {
            plannedRemovalAt = TimeUtils.fromIso8601String(req.getPlannedRemovalAtIso8601(), "UTC");
            if (!plannedRemovalAt.isAfter(removedAt)) {
                log.warn("planed_removal_at is reset to null, since it is before the insersion point, req: {}, accountid: {}",
                    req, accountId);
                plannedRemovalAt = null;
            }
        }

        // 更新旧管道记录的拔管信息
        oldRecord.setIsUnplannedRemoval(false);  // 默认设置为false
        if (!StrUtils.isBlank(req.getRemovalReason())) oldRecord.setRemovalReason(req.getRemovalReason());

        String removedBy = StrUtils.isBlank(req.getRemovedBy()) ? accountId : req.getRemovedBy();
        String removedByAccountName = userService.getNameByAutoId(removedBy);
        oldRecord.setRemovedBy(removedBy);
        oldRecord.setRemovedByAccountName(removedByAccountName);
        oldRecord.setRemovedAt(removedAt);
        ptRecordRepo.save(oldRecord);  // 保存更新后的旧管道记录

        // 一致性弱检查
        if (req.getNewTubeTypeId() != oldRecord.getTubeTypeId()) {
            log.warn("Tube type id not match, oldTubeTypeId: {}, newTubeTypeId: {}, req: {}, accountid: {}",
                oldRecord.getTubeTypeId(), req.getNewTubeTypeId(), req, accountId);
        }
        if (!req.getNewTubeName().equals(oldRecord.getTubeName())) {
            log.warn("Tube name not match, oldTubeName: {}, newTubeName: {}, req: {}, accountid: {}",
                oldRecord.getTubeName(), req.getNewTubeName(), req, accountId);
        }

        // 创建新管道记录，继承旧管道的主要信息
        PatientTubeRecord newRecord = PatientTubeRecord.builder()
            .pid(oldRecord.getPid())
            .tubeTypeId(oldRecord.getTubeTypeId())  // 继承旧管道的 tubeTypeId
            .tubeName(oldRecord.getTubeName())      // 继承旧管道的 tubeName
            .rootTubeRecordId(oldRecord.getRootTubeRecordId())  // 设置根管道
            .prevTubeRecordId(oldRecord.getId())     // 设置前一管道
            .insertedBy(oldRecord.getRemovedBy())   // 使用旧管道的 removedBy 作为新管道的插入人
            .insertedAt(removedAt)                  // 使用旧管道的 removedAt 作为新管道的插入时间
            .plannedRemovalAt(plannedRemovalAt)
            .note(req.getNote())
            .isDeleted(false)
            .build();

        // 保存新管道记录
        PatientTubeRecord savedNewRecord = ptRecordRepo.save(newRecord);

        // 处理属性列表
        if (req.getAttributesCount() > 0) {
            for (TubeAttributePB attribute : req.getAttributesList()) {
                PatientTubeAttr patientTubeAttr = PatientTubeAttr.builder()
                    .patientTubeRecordId(savedNewRecord.getId())
                    .tubeAttrId(attribute.getTubeAttrId())
                    .value(attribute.getValue())
                    .build();
                ptAttrRepo.save(patientTubeAttr);
            }
        }
        log.info("Tube record replaced successfully, oldRecordId: {}, newRecordId: {}, replacedBy: {}", 
                oldRecord.getId(), savedNewRecord.getId(), accountId);

        return savedNewRecord.getId();
    }

    @Transactional
    public void updatePatientTubeAttr(UpdatePatientTubeAttrReq req, PatientTubeRecord record, String accountId) {
        // 更新备注信息
        if (!StrUtils.isBlank(req.getNote())) {
            record.setNote(req.getNote());
            ptRecordRepo.save(record); // 更新记录到数据库
        }

        // 处理属性更新
        if (req.getAttrsCount() > 0) {
            List<TubeAttributePB> attrs = req.getAttrsList();

            // 遍历并更新属性值
            for (TubeAttributePB attrPb : attrs) {
                // 查找属性记录，如果不存在则创建新的
                PatientTubeAttr attr = ptAttrRepo.findByPatientTubeRecordIdAndTubeAttrId(
                        req.getPatientTubeRecordId(), attrPb.getTubeAttrId())
                    .orElseGet(() -> PatientTubeAttr.builder()
                        .patientTubeRecordId(record.getId())
                        .tubeAttrId(attrPb.getTubeAttrId())
                        .build());

                // 更新属性值
                attr.setValue(attrPb.getValue());
                ptAttrRepo.save(attr);
            }
        }

        log.info("Patient tube attributes updated successfully, recordId: {}, updatedBy: {}", 
                record.getId(), accountId);
    }

    @Transactional
    public List<TubeTimeStatusValListPB> fetchPatientTubeStatuses(
        Long patientTubeRecordId, LocalDateTime startDate, LocalDateTime endDate, String zoneId
    ) {
        // 查询状态记录
        if (startDate == null) startDate = TimeUtils.getLocalTime(1900, 1, 1);
        if (endDate == null) endDate = TimeUtils.getLocalTime(9999, 1, 1);
        List<PatientTubeStatusRecord> statusRecords = ptStatusRecordRepo
            .findByPatientTubeRecordIdAndIsDeletedFalseAndRecordedAtBetween(
                patientTubeRecordId, startDate, endDate
            );

        // 获取 tube_status_id 对应的 display_order 信息
        List<Integer> statusIds = statusRecords.stream()
            .map(PatientTubeStatusRecord::getTubeStatusId)
            .distinct()
            .collect(Collectors.toList());

        Map<Integer, Integer> displayOrderMap = tubeTypeStatusRepo.findDisplayOrderByStatusIds(statusIds).stream()
            .collect(Collectors.toMap(AttributeDisplayOrder::getId, AttributeDisplayOrder::getDisplayOrder));

        // 按 recorded_at 分组并生成 TubeTimeStatusValListPB
        return statusRecords.stream()
            .collect(Collectors.groupingBy(
                record -> record.getRecordedAt()
            ))
            .entrySet().stream()
            .map(entry -> {
                // 排序每个时间组内的 TubeStatusValPB
                List<TubeStatusValPB> sortedStatusList = entry.getValue().stream()
                    .map(statusRecord -> TubeStatusValPB.newBuilder()
                        .setTubeStatusId(statusRecord.getTubeStatusId())
                        .setValue(statusRecord.getValue())
                        .build())
                    .sorted(Comparator
                        .comparingInt((TubeStatusValPB status) -> displayOrderMap.getOrDefault(status.getTubeStatusId(), Integer.MAX_VALUE))
                        .thenComparingLong(TubeStatusValPB::getTubeStatusId))
                    .collect(Collectors.toList());

                String recordedBy = entry.getValue().isEmpty() ? "" :
                    entry.getValue().get(0).getRecordedBy();
                String recordedByAccountName = entry.getValue().isEmpty() ? "" :
                    entry.getValue().get(0).getRecordedByAccountName();

                // 构造 TubeTimeStatusValListPB
                return TubeTimeStatusValListPB.newBuilder()
                    .setRecordedBy(recordedBy)
                    .setRecordedByAccountName(recordedByAccountName)
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(entry.getKey(), zoneId))
                    .addAllStatus(sortedStatusList)
                    .build();
            })
            .sorted(Comparator.comparing(TubeTimeStatusValListPB::getRecordedAtIso8601))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<PatientTubeStatusRecord> fetchPatientTubeStatuses(
        List<Long> patientTubeRecordIds, LocalDateTime startDate, LocalDateTime endDate
    ) {
        return ptStatusRecordRepo.findByPatientTubeRecordIdInAndIsDeletedFalseAndRecordedAtBetween(
            patientTubeRecordIds, startDate, endDate
        );
    }

    @Transactional
    public List<LocalDateTime> fetchPatientTubeStatusRows(
        Long patientTubeRecordId, LocalDateTime startDate, LocalDateTime endDate
    ) {
        if (startDate == null) startDate = TimeUtils.getLocalTime(1900, 1, 1);
        if (endDate == null) endDate = TimeUtils.getLocalTime(9999, 1, 1);
        return ptStatusRecordRepo.findByPatientTubeRecordIdAndIsDeletedFalseAndRecordedAtBetween(patientTubeRecordId, startDate, endDate).stream().map(PatientTubeStatusRecord::getRecordedAt).collect(Collectors.toSet()).stream().sorted().toList();
    }

    public boolean checkExistingStatus(NewPatientTubeStatusReq req) {
        LocalDateTime recordedAt = TimeUtils.fromIso8601String(req.getTimeStatus().getRecordedAtIso8601(), "UTC");
        List<TubeStatusValPB> statuses = req.getTimeStatus().getStatusList();

        for (TubeStatusValPB status : statuses) {
            boolean exists = ptStatusRecordRepo.existsByPatientTubeRecordIdAndTubeStatusIdAndRecordedAtAndIsDeletedFalse(
                req.getPatientTubeRecordId(),
                status.getTubeStatusId(),
                recordedAt
            );
            if (exists) {
                log.warn("Status already exists for patientTubeRecordId: {}, tubeStatusId: {}, recordedAt: {}",
                        req.getPatientTubeRecordId(), status.getTubeStatusId(), recordedAt);
                return true;  // 存在重复状态
            }
        }
        return false;  // 不存在重复状态
    }

    @Transactional
    public void newPatientTubeStatus(NewPatientTubeStatusReq req, String accountId, String accountName) {
        // 确认状态记录的时间和属性
        LocalDateTime recordedAt = TimeUtils.fromIso8601String(req.getTimeStatus().getRecordedAtIso8601(), "UTC");
        List<TubeStatusValPB> statuses = req.getTimeStatus().getStatusList();

        // 将状态按 display_order 和 tube_status_id 排序
        List<TubeStatusValPB> sortedStatuses = statuses.stream()
            .sorted(Comparator.comparing((TubeStatusValPB status) -> {
                TubeTypeStatus tubeTypeStatus = tubeTypeStatusRepo.findById(status.getTubeStatusId()).orElse(null);
                return tubeTypeStatus != null ? tubeTypeStatus.getDisplayOrder() : Integer.MAX_VALUE;
            }).thenComparing(TubeStatusValPB::getTubeStatusId))
            .collect(Collectors.toList());

        // 创建和保存状态记录
        for (TubeStatusValPB status : sortedStatuses) {
            PatientTubeStatusRecord statusRecord = PatientTubeStatusRecord.builder()
                .patientTubeRecordId(req.getPatientTubeRecordId())
                .tubeStatusId(status.getTubeStatusId())
                .value(status.getValue())
                .recordedBy(accountId)
                .recordedByAccountName(accountName)
                .recordedAt(recordedAt)
                .isDeleted(false)
                .build();
            ptStatusRecordRepo.save(statusRecord);
        }

        log.info("Added new tube status for record id: {}, recorded by: {}", req.getPatientTubeRecordId(), accountId);
    }

    @Transactional
    public List<PatientTubeStatusRecord> deletePatientTubeStatus(DeletePatientTubeStatusReq req, String accountId) {
        LocalDateTime recordedAt = TimeUtils.fromIso8601String(req.getRecordedAtIso8601(), "UTC");

        // 查找是否存在对应的管道状态记录
        List<PatientTubeStatusRecord> statusRecords = ptStatusRecordRepo
            .findByPatientTubeRecordIdAndRecordedAt(req.getPatientTubeRecordId(), recordedAt);

        if (statusRecords.isEmpty()) {
            log.warn("No status records found for deletion with patientTubeRecordId: {} and recordedAt: {}",
                    req.getPatientTubeRecordId(), recordedAt);
            return Collections.emptyList();
        }

        // 设置 isDeleted 属性并添加删除元数据
        for (PatientTubeStatusRecord statusRecord : statusRecords) {
            statusRecord.setIsDeleted(true);
            statusRecord.setDeletedBy(accountId);
            statusRecord.setDeletedAt(TimeUtils.getNowUtc());
            statusRecord.setDeleteReason("Deleted via deletePatientTubeStatus API");
            ptStatusRecordRepo.save(statusRecord);
        }

        log.info("Successfully deleted status records for patientTubeRecordId: {} at recordedAt: {}, deleted by {}",
                req.getPatientTubeRecordId(), recordedAt, accountId);
        return statusRecords;
    }

    public List<String> getMonitoringParamCodes(
        Long patientId, LocalDateTime queryStart, LocalDateTime queryEnd
    ) {
        Map<String, String> codeNameMap = getMonitoringParamCodeNames(patientId, queryStart, queryEnd);
        List<String> codes = new ArrayList<>(codeNameMap.keySet());

        return codes.stream().distinct().sorted().toList();
    }

    @Transactional
    public Map<String/*drainage tube code*/, String/*drainage tube name*/> getMonitoringParamCodeNames(
        Long patientId, LocalDateTime queryStart, LocalDateTime queryEnd
    ) {
        if (queryStart == null) {
            queryStart = TimeUtils.getLocalTime(1900, 1, 1);
        }
        if (queryEnd == null) {
            queryEnd = TimeUtils.getLocalTime(9999, 1, 1);
        }

        // 找出时间范围内的插管记录
        List<PatientTubeRecord> records = ptRecordRepo.findByPidAndDateRange(
            patientId, queryStart, queryEnd
        );
        List<Integer> tubeTypeIds = records.stream()
            .map(PatientTubeRecord::getTubeTypeId)
            .collect(Collectors.toList());
        Map<Integer, TubeType> tubeTypeMap = tubeTypeRepo.findByIdInAndIsDeletedFalse(tubeTypeIds)
            .stream()
            .collect(Collectors.toMap(TubeType::getId, tubeType -> tubeType));

        // 根据rootId找出对应的插管记录
        List<Long> rootRecordIds = records.stream()
            .filter(record -> record.getRootTubeRecordId() != null &&
                !record.getId().equals(record.getRootTubeRecordId())
            )
            .map(PatientTubeRecord::getRootTubeRecordId)
            .toList();
        Map<Long, PatientTubeRecord> rootRecordMap = ptRecordRepo.findByIdInAndIsDeletedFalse(rootRecordIds)
            .stream()
            .collect(Collectors.toMap(PatientTubeRecord::getId, record -> record));

        // 找出对应的监测参数
        Map<String, String> codeNameMap = new HashMap<>();
        for (PatientTubeRecord record : records) {
            TubeType tubeType = tubeTypeMap.get(record.getTubeTypeId());
            if (tubeType == null) {
                log.warn("Tube type not found for tubeTypeId: {}", record.getTubeTypeId());
                continue;
            }

            if (!tubeType.getType().equals(DRAINAGE_TUBE_TYPE)) {
                continue;
            }

            // 找出根管道名称
            String tubeName = record.getTubeName();
            PatientTubeRecord rootRecord = rootRecordMap.get(record.getRootTubeRecordId());
            if (rootRecord != null) {
                tubeName = rootRecord.getTubeName();
            }
            codeNameMap.put(tubeSetting.getMonitoringParamCode(DRAINAGE_TUBE_TYPE, tubeName), tubeName);
        }

        return codeNameMap;
    }

    private final String DRAINAGE_TUBE_TYPE;

    private final UserService userService;  // 用户服务
    private final TubeSetting tubeSetting;  // 管道设置
    private final TubeTypeRepository tubeTypeRepo;  // 管道类型仓库
    private final TubeTypeAttributeRepository tubeTypeAttrRepo;  // 管道类型属性仓库
    private final TubeTypeStatusRepository tubeTypeStatusRepo;  // 管道类型状态仓库
    private final PatientTubeRecordRepository ptRecordRepo;  // 病人插管记录仓库
    private final PatientTubeAttrRepository ptAttrRepo;  // 病人管道属性仓库
    private final PatientTubeStatusRecordRepository ptStatusRecordRepo;  // 病人管道状态记录仓库
}
