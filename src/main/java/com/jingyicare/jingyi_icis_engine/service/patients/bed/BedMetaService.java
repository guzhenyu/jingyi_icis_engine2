package com.jingyicare.jingyi_icis_engine.service.patients.bed;

import static com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils.genericResponse;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.BedCount;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddBedConfigReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddBedConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddBedCountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteBedConfigReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteBedCountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetBedConfigReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetBedConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetBedCountsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetBedCountsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedCountRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedJdbc;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedJdbc.ConfigLocation;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedJdbc.CountLocation;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class BedMetaService {
    public BedMetaService(
        ProtoConfig protoConfig, BedMetaReader reader, CertificateService certificateService,
        UserQuery userQuery, PatientRecordRepository patientRepo, BedConfigRepository configRepo,
        BedCountRepository countRepo, JdbcTemplate jdbc, LockExecutor lockExecutor
    ) {
        this.statusCodeMsgList = protoConfig.getConfig().getText().getStatusCodeMsgList();
        this.zoneId = protoConfig.getConfig().getZoneId();
        this.reader = reader;
        this.certificateService = certificateService;
        this.userQuery = userQuery;
        this.patientRepo = patientRepo;
        this.configRepo = configRepo;
        this.countRepo = countRepo;
        this.jdbc = jdbc;
        this.lockExecutor = lockExecutor;
    }

    public GetBedCountsResp getBedCounts(String json) {
        GetBedCountsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, GetBedCountsReq.newBuilder());
        } catch (Exception e) {
            log.error("解析床位数量查询请求失败", e);
            return GetBedCountsResp.newBuilder().setRt(returnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        String deptId = req.getDeptId();
        List<BedCount> counts = StrUtils.isBlank(deptId)
            ? countRepo.findByIsDeletedFalseOrderByDeptIdAscEffectiveTimeDesc()
            : countRepo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId);
        return GetBedCountsResp.newBuilder().setRt(returnCode(StatusCode.OK))
            .addAllBedCount(counts.stream().map(b -> BedMapper.toBedCountPB(b, zoneId)).toList())
            .build();
    }

    public GenericResp addBedCount(String json) {
        AddBedCountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, AddBedCountReq.newBuilder());
        } catch (Exception e) {
            log.error("解析新增床位数量请求失败", e);
            return genericResponse(returnCode(StatusCode.PARSE_JSON_FAILED));
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return genericResponse(returnCode(StatusCode.ACCOUNT_NOT_FOUND));

        String deptId = req.getDeptId();
        LocalDateTime effectiveTime = TimeUtils.roundToMicros(
            TimeUtils.fromIso8601String(req.getBedCount().getDateIso8601(), "UTC"));
        if (effectiveTime == null) {
            return genericResponse(returnCode(StatusCode.INVALID_TIME_FORMAT));
        }

        StatusCode status = lockExecutor.execute(List.of(LockKeys.BedCount(deptId, effectiveTime)),
            () -> {
                if (countRepo.findByDeptIdAndEffectiveTimeAndIsDeletedFalse(deptId, effectiveTime)
                    .isPresent()) {
                    return StatusCode.BED_COUNT_ALREADY_EXISTS;
                }
                BedCount count = BedMapper.toBedCountEntity(
                    deptId, req.getBedCount(), effectiveTime);
                count.setModifiedBy(account.autoAcctId());
                count.setModifiedAt(TimeUtils.getNowUtc());
                count = countRepo.save(count);
                log.info("新增床位数量记录 id={}，操作人={}", count.getId(), account.acctName());
                return StatusCode.OK;
            });
        return genericResponse(returnCode(status));
    }

    public GenericResp deleteBedCount(String json) {
        DeleteBedCountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, DeleteBedCountReq.newBuilder());
        } catch (Exception e) {
            log.error("解析删除床位数量请求失败", e);
            return genericResponse(returnCode(StatusCode.PARSE_JSON_FAILED));
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return genericResponse(returnCode(StatusCode.ACCOUNT_NOT_FOUND));

        int id = req.getId();
        Supplier<CountLocation> load = () -> BedJdbc.findActiveCountLocation(jdbc, id).orElse(null);
        StatusCode status = lockExecutor.executePlannedIfPresent(load,
            key -> List.of(LockKeys.BedCount(key.deptId(), key.effectiveTimeUtc())),
            key -> key.equals(load.get()), key -> {
                BedCount count = countRepo.findByIdAndIsDeletedFalse(id).orElse(null);
                if (count == null) return StatusCode.OK;
                count.setIsDeleted(true);
                count.setDeletedBy(account.autoAcctId());
                count.setDeletedAt(TimeUtils.getNowUtc());
                countRepo.save(count);
                log.info("删除床位数量记录 id={}，操作人={}", id, account.acctName());
                return StatusCode.OK;
            }, StatusCode.OK);
        return genericResponse(returnCode(status));
    }

    public GetBedConfigResp getBedConfig(String json) {
        GetBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, GetBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("解析床位配置查询请求失败", e);
            return GetBedConfigResp.newBuilder().setRt(returnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        String deptId = req.getDeptId();
        Map<String, PatientRecord> patients = new HashMap<>();
        for (PatientRecord patient : patientRepo.findByDeptIdAndAdmissionStatus(
            deptId, PatientEnums.ADMISSION_STATUS_ADMITTED)) {
            PatientRecord previous = patients.put(patient.getHisBedNumber(), patient);
            if (previous != null) {
                log.error("已入科患者占用相同 HIS 床号，患者 id={}、{}", previous.getId(), patient.getId());
            }
        }
        List<BedConfig> beds = StrUtils.isBlank(deptId)
            ? configRepo.findAllByIsDeletedFalse() : reader.findActiveBedsByDepartment(deptId);
        return GetBedConfigResp.newBuilder().setRt(returnCode(StatusCode.OK))
            .addAllBedConfig(beds.stream().sorted(Comparator.comparing(BedConfig::getHisBedNumber))
                .map(b -> BedMapper.toBedConfigPB(b, patients.get(b.getHisBedNumber()))).toList())
            .build();
    }

    public AddBedConfigResp addBedConfig(String json) {
        AddBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, AddBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("解析新增床位配置请求失败", e);
            return addConfigError(StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return addConfigError(StatusCode.ACCOUNT_NOT_FOUND);

        BedConfigPB bed = req.getBedConfig().toBuilder().clearId().build();
        String deptId = bed.getDepartmentId();
        return lockExecutor.execute(List.of(LockKeys.bedConfig(deptId)), () -> {
            StatusCode conflict = checkBedNumbers(bed, null);
            if (conflict != StatusCode.OK) return addConfigError(conflict);
            if (!certificateService.checkBedAvailable(deptId, bed.getBedType(), 1)) {
                return addConfigError(StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED);
            }
            BedConfig saved = saveConfig(bed, account);
            log.info("新增床位配置 id={}，操作人={}", saved.getId(), account.acctName());
            return AddBedConfigResp.newBuilder().setRt(returnCode(StatusCode.OK))
                .setId(saved.getId()).build();
        });
    }

    public GenericResp updateBedConfig(String json) {
        AddBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, AddBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("解析更新床位配置请求失败", e);
            return genericResponse(returnCode(StatusCode.PARSE_JSON_FAILED));
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return genericResponse(returnCode(StatusCode.ACCOUNT_NOT_FOUND));

        BedConfigPB bed = req.getBedConfig();
        StatusCode status = withExistingConfigLocks(bed.getId(), () -> {
            BedConfig current = configRepo.findByIdAndIsDeletedFalse(bed.getId()).orElse(null);
            if (current == null) return StatusCode.BED_CONFIG_NOT_EXISTS;
            BedConfigPB updated = bed.toBuilder()
                .setDepartmentId(current.getDepartmentId()).build();
            StatusCode conflict = checkBedNumbers(updated, current.getId());
            if (conflict != StatusCode.OK) return conflict;
            if (!Objects.equals(current.getBedType(), updated.getBedType())
                && !certificateService.checkBedAvailable(
                    current.getDepartmentId(), updated.getBedType(), 1)) {
                return StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED;
            }
            saveConfig(updated, account);
            log.info("更新床位配置 id={}，操作人={}", bed.getId(), account.acctName());
            return StatusCode.OK;
        }, StatusCode.BED_CONFIG_NOT_EXISTS);
        return genericResponse(returnCode(status));
    }

    public GenericResp deleteBedConfig(String json) {
        DeleteBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, DeleteBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("解析删除床位配置请求失败", e);
            return genericResponse(returnCode(StatusCode.PARSE_JSON_FAILED));
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return genericResponse(returnCode(StatusCode.ACCOUNT_NOT_FOUND));

        long id = req.getId();
        StatusCode status = withExistingConfigLocks(id, () -> {
            BedConfig bed = configRepo.findByIdAndIsDeletedFalse(id).orElse(null);
            if (bed == null) return StatusCode.OK;
            bed.setIsDeleted(true);
            bed.setDeletedBy(account.autoAcctId());
            bed.setDeletedAt(TimeUtils.getNowUtc());
            configRepo.save(bed);
            log.info("删除床位配置 id={}，操作人={}", id, account.acctName());
            return StatusCode.OK;
        }, StatusCode.OK);
        return genericResponse(returnCode(status));
    }

    private <T> T withExistingConfigLocks(long id, Supplier<T> action, T missingResult) {
        Supplier<ConfigLocation> load = () -> BedJdbc.findConfigLocation(jdbc, id).orElse(null);
        return lockExecutor.executePlannedIfPresent(load,
            location -> List.of(LockKeys.bedConfig(location.deptId())),
            location -> location.equals(load.get()), location -> action.get(), missingResult);
    }

    private StatusCode checkBedNumbers(BedConfigPB bed, Long updatingId) {
        for (BedConfig current : reader.findActiveBedsByDepartment(bed.getDepartmentId())) {
            if (Objects.equals(updatingId, current.getId())) continue;
            if (updatingId == null && bed.getHisBedNumber().equals(current.getHisBedNumber())) {
                return StatusCode.BED_CONFIG_ALREADY_EXISTS;
            }
            if (bed.getDisplayBedNumber().equals(current.getDisplayBedNumber())) {
                return StatusCode.BED_CONFIG_DISPLAY_NAME_ALREADY_EXISTS;
            }
            if (bed.getDeviceBedNumber().equals(current.getDeviceBedNumber())) {
                return StatusCode.BED_CONFIG_DEVICE_NAME_ALREADY_EXISTS;
            }
        }
        return StatusCode.OK;
    }

    private BedConfig saveConfig(BedConfigPB bed, CurrentAccount account) {
        BedConfig entity = BedMapper.toBedConfigEntity(bed);
        entity.setModifiedBy(account.autoAcctId());
        entity.setModifiedByAccountName(account.acctName());
        entity.setModifiedAt(TimeUtils.getNowUtc());
        return configRepo.save(entity);
    }

    private ReturnCode returnCode(StatusCode status) {
        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, status);
    }

    private AddBedConfigResp addConfigError(StatusCode status) {
        return AddBedConfigResp.newBuilder().setRt(returnCode(status)).build();
    }

    private final List<String> statusCodeMsgList;
    private final String zoneId;
    private final BedMetaReader reader;
    private final CertificateService certificateService;
    private final UserQuery userQuery;
    private final PatientRecordRepository patientRepo;
    private final BedConfigRepository configRepo;
    private final BedCountRepository countRepo;
    private final JdbcTemplate jdbc;
    private final LockExecutor lockExecutor;
}
