package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class PatientMonitoringMetaService {
    public PatientMonitoringMetaService(
        PatientReader patientReader,
        LockExecutor lockExecutor,
        DeptMonitoringGroupRepository deptGroupRepository,
        PatientMonitoringGroupRepository patientGroupRepository,
        PatientMonitoringGroupParamRepository patientGroupParamRepository
    ) {
        this.patientReader = patientReader;
        this.lockExecutor = lockExecutor;
        this.deptGroupRepository = deptGroupRepository;
        this.patientGroupRepository = patientGroupRepository;
        this.patientGroupParamRepository = patientGroupParamRepository;
    }

    // 挂起报表、用药等调用方的事务，元数据读取在独立的短事务内完成。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public <T> T readUnderLock(long pid, Supplier<T> action) {
        return lockExecutor.execute(List.of(LockKeys.patientMonitoringGroups(pid)), action);
    }

    // 调用方必须在同一事务内持有该患者的监测分组业务锁。
    List<PatientMonitoringGroup> findOrInitGroups(
        long pid, String deptId, String accountId
    ) {
        List<PatientMonitoringGroup> patientGroups = patientGroupRepository
            .findByPidAndIsDeletedFalse(pid);
        if (!patientGroups.isEmpty()) return patientGroups;

        PatientRecord patient = patientReader.findById(pid).orElse(null);
        if (patient == null) {
            log.warn("无法初始化监测分组，患者不存在：pid={}", pid);
            return List.of();
        }
        if (!Objects.equals(patient.getDeptId(), deptId)) {
            log.warn("无法初始化监测分组，患者科室不匹配：pid={}, deptId={}, patientDeptId={}",
                pid, deptId, patient.getDeptId());
            return List.of();
        }
        List<DeptMonitoringGroup> deptGroups = deptGroupRepository
            .findByDeptIdAndIsDeletedFalse(deptId);
        return createGroups(pid, deptGroups, accountId, TimeUtils.getNowUtc());
    }

    // 参数更新入口已取得患者监测分组业务锁，初始化前复查现有绑定。
    List<PatientMonitoringGroup> findOrInitGroupsForLockedPatient(
        PatientRecord patient, String accountId
    ) {
        List<PatientMonitoringGroup> patientGroups = patientGroupRepository
            .findByPidAndIsDeletedFalse(patient.getId());
        if (!patientGroups.isEmpty()) return patientGroups;

        List<DeptMonitoringGroup> deptGroups = deptGroupRepository
            .findByDeptIdAndIsDeletedFalse(patient.getDeptId());
        return createGroups(patient.getId(), deptGroups, accountId, TimeUtils.getNowUtc());
    }

    public void syncFromDepartment(long pid, String deptId, String accountId) {
        lockExecutor.execute(List.of(LockKeys.patientMonitoringGroups(pid)), () -> {
            PatientRecord patient = patientReader.findById(pid).orElse(null);
            if (patient == null || !Objects.equals(patient.getDeptId(), deptId)) return null;

            List<DeptMonitoringGroup> deptGroups = deptGroupRepository
                .findByDeptIdAndIsDeletedFalse(deptId);
            List<PatientMonitoringGroup> patientGroups = patientGroupRepository
                .findByPidAndIsDeletedFalse(pid);
            Set<Integer> deptGroupIds = deptGroups.stream()
                .map(DeptMonitoringGroup::getId).collect(Collectors.toSet());
            Set<Integer> boundDeptGroupIds = patientGroups.stream()
                .map(PatientMonitoringGroup::getDeptMonitoringGroupId).collect(Collectors.toSet());
            List<Integer> patientGroupIds = patientGroups.stream()
                .map(PatientMonitoringGroup::getId).toList();
            boolean hasPatientParams = !patientGroupIds.isEmpty() && !patientGroupParamRepository
                .findByPatientMonitoringGroupIdInAndIsDeletedFalse(patientGroupIds).isEmpty();
            if (patientGroups.size() == deptGroupIds.size()
                && deptGroupIds.equals(boundDeptGroupIds) && !hasPatientParams) {
                return null;
            }

            LocalDateTime now = TimeUtils.getNowUtc();
            for (PatientMonitoringGroup group : patientGroups) {
                group.setIsDeleted(true);
                group.setDeletedBy(accountId);
                group.setDeletedAt(now);
            }
            // 先刷新软删除，再插入同一科室组的新绑定，兼容有效绑定的唯一约束。
            if (!patientGroups.isEmpty()) patientGroupRepository.saveAllAndFlush(patientGroups);
            createGroups(pid, deptGroups, accountId, now);
            return null;
        });
    }

    private List<PatientMonitoringGroup> createGroups(
        long pid, List<DeptMonitoringGroup> deptGroups, String accountId, LocalDateTime now
    ) {
        List<PatientMonitoringGroup> patientGroups = new ArrayList<>();
        for (DeptMonitoringGroup deptGroup : deptGroups) {
            patientGroups.add(PatientMonitoringGroup.builder()
                .deptMonitoringGroupId(deptGroup.getId())
                .pid(pid)
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(now)
                .build());
        }
        if (patientGroups.isEmpty()) return patientGroups;
        return patientGroupRepository.saveAll(patientGroups);
    }

    private final PatientReader patientReader;
    private final LockExecutor lockExecutor;
    private final DeptMonitoringGroupRepository deptGroupRepository;
    private final PatientMonitoringGroupRepository patientGroupRepository;
    private final PatientMonitoringGroupParamRepository patientGroupParamRepository;
}
