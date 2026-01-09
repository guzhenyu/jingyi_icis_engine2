package com.jingyicare.jingyi_icis_engine.utils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.*;
import java.sql.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class MonitoringRecordUtils {
    public static final String DELETED_REASON_UPDATED = "updated";
    public static final String DELETED_REASON_DELETED = "deleted";

    public MonitoringRecordUtils(
        PatientMonitoringRecordRepository recordRepo,
        PatientMonitoringRecordStatsDailyRepository dailyStatsRepo
    ) {
        this.recordRepo = recordRepo;
        this.dailyStatsRepo = dailyStatsRepo;
    }

    // 平衡组：每小时入量， 每小时出量 按需保存
    // 更新规则：
    // - 若无旧记录 → 新增记录
    // - 若存在旧记录但内容不同 → 删除旧，新增新
    // - 若存在旧记录但新记录中缺失该时间点 → 删除旧记录
    // 函数说明：records如果不为空，records中的pid, paramCode, modifiedAt, modifiedBy与参数是一致的
    @Transactional(rollbackFor = Exception.class)
    public boolean saveBalanceSumRecords(
        LocalDateTime startTime, LocalDateTime endTime, String modifiedBy,
        Long pid, String deptId, String paramCode, ValueMetaPB valueMeta,
        Map<LocalDateTime, GenericValuePB> timeValMap
    ) {
        if (!validateBalanceSumParams(startTime, endTime, modifiedBy, pid, deptId, paramCode, valueMeta, timeValMap)) return false;
        if (timeValMap.isEmpty()) {
            log.info("Empty records list provided; deleting all existing records in range {} - {} for pid={}, paramCode={}", 
                startTime, endTime, pid, paramCode);
        }

        // 统计时间范围内的现有记录
        Map<LocalDateTime, PatientMonitoringRecord> existingRecordMap = recordRepo
            .findByPidAndParamCodeAndEffectiveTimeRange(pid, paramCode, startTime, endTime)
            .stream()
            .collect(Collectors.toMap(
                PatientMonitoringRecord::getEffectiveTime,
                rec -> rec,
                (rec1, rec2) -> rec1  // 如果有重复，保留第一个
            ));

        // 更新记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<PatientMonitoringRecord> recordsToDelete = new ArrayList<>();
        List<PatientMonitoringRecord> recordsToAdd = new ArrayList<>();
        for (Map.Entry<LocalDateTime, GenericValuePB> entry : timeValMap.entrySet()) {
            LocalDateTime effectiveTime = entry.getKey();
            GenericValuePB newValue = entry.getValue();
            PatientMonitoringRecord newRecord = newMonitoringRecord(
                pid, deptId, paramCode, effectiveTime, valueMeta, newValue, modifiedBy, nowUtc
            );

            PatientMonitoringRecord existingRecord = existingRecordMap.get(effectiveTime);
            if (existingRecord == null) {
                recordsToAdd.add(newRecord);
                continue;
            }

            // 判断是否真的需要更新
            boolean toUpdate = diff(existingRecord, newRecord);
            if (!toUpdate) continue;

            // 逻辑删除旧记录，保存新记录
            existingRecord.setIsDeleted(true);
            existingRecord.setDeleteReason(DELETED_REASON_UPDATED);
            existingRecord.setDeletedAt(nowUtc);
            existingRecord.setDeletedBy(modifiedBy);
            recordsToDelete.add(existingRecord);
            recordsToAdd.add(newRecord);
        }

        // 删除不在新记录中的旧记录
        Set<LocalDateTime> timesToDelete = new HashSet<>(existingRecordMap.keySet());
        timesToDelete.removeAll(timeValMap.keySet());
        for (LocalDateTime time : timesToDelete) {
            PatientMonitoringRecord oldRecord = existingRecordMap.get(time);
            if (oldRecord == null) continue;
            oldRecord.setIsDeleted(true);
            oldRecord.setDeleteReason(DELETED_REASON_DELETED);
            oldRecord.setDeletedAt(nowUtc);
            oldRecord.setDeletedBy(modifiedBy);
            recordsToDelete.add(oldRecord);
        }

        if (recordsToDelete.isEmpty() && recordsToAdd.isEmpty()) {
            log.info("No changes detected for balance sum records in range {} - {} for pid={}, paramCode={}",
                startTime, endTime, pid, paramCode);
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            for (PatientMonitoringRecord del : recordsToDelete) {
                updateIsDeleted(conn, del);
            }
            conn.commit();

            conn.setAutoCommit(false); // 重新开始新事务
            for (PatientMonitoringRecord add : recordsToAdd) {
                insertRecord(conn, add, true);
            }
            conn.commit();
        } catch (Exception e) {
            log.error("Failed to save balance sum records in JDBC mode", e);
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public PatientMonitoringRecord saveRecord(PatientMonitoringRecord record) {
        if (!isValidNewRecord(record)) return null;

        // 根据 pid + param + time 查找是否已存在有效记录
        PatientMonitoringRecord existingRecord = recordRepo
            .findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(
                record.getPid(), record.getMonitoringParamCode(), record.getEffectiveTime()
            ).orElse(null);
        if (existingRecord == null) {
            try {
                record.setId(null);
                return recordRepo.save(record);
            } catch (Exception e) {
                log.error("Failed to save record: {}", e.getMessage(), e);
                throw e;
            }
        }

        // 判断是否真的需要更新
        boolean toUpdate = diff(existingRecord, record);
        if (!toUpdate) return existingRecord;

        // 逻辑删除旧记录，保存新记录
        existingRecord.setIsDeleted(true);
        existingRecord.setDeleteReason(DELETED_REASON_UPDATED);
        existingRecord.setDeletedAt(record.getModifiedAt());
        existingRecord.setDeletedBy(record.getModifiedBy());
        record.setId(null);

        try {
            recordRepo.save(existingRecord);
            return recordRepo.save(record);
        } catch (Exception e) {
            log.error("Failed to save record: {}", e.getMessage(), e);
            throw e;
        }
    }

    public PatientMonitoringRecord saveRecordJdbc(PatientMonitoringRecord record) {
        if (!isValidNewRecord(record)) return null;

        for (int attempt = 1; attempt <= SAVE_RECORD_MAX_RETRIES; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false); // 手动控制事务

                try {
                    // 查询是否存在有效记录
                    PatientMonitoringRecord existing = null;
                    try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT * FROM patient_monitoring_records
                        WHERE pid = ? AND monitoring_param_code = ? AND effective_time = ? AND is_deleted = false
                        FOR UPDATE
                    """)) {
                        ps.setLong(1, record.getPid());
                        ps.setString(2, record.getMonitoringParamCode());
                        ps.setTimestamp(3, Timestamp.valueOf(record.getEffectiveTime()));

                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                existing = mapRow(rs);
                            }
                        }
                    }

                    // 不存在则直接插入
                    if (existing == null) {
                        insertRecord(conn, record, false);
                        conn.commit();
                        return record;
                    }

                    // 如果相同则返回原记录
                    if (!diff(existing, record)) {
                        conn.commit();
                        return existing;
                    }

                    // 标记旧记录为删除，并插入新记录（同一事务）
                    existing.setDeleteReason(DELETED_REASON_UPDATED);
                    existing.setDeletedBy(record.getModifiedBy());
                    existing.setDeletedAt(record.getModifiedAt());
                    updateIsDeleted(conn, existing);
                    insertRecord(conn, record, false);
                    conn.commit();

                    return record;
                } catch (SQLException e) {
                    conn.rollback();
                    if (isDeadlockOrSerializationFailure(e) && attempt < SAVE_RECORD_MAX_RETRIES) {
                        backoffSleep(attempt);
                        continue;
                    }
                    log.error("JDBC saveRecord failed", e);
                    return null;
                }
            } catch (Exception e) {
                log.error("JDBC saveRecord failed", e);
                return null;
            }
        }
        return null;
    }

    @Transactional
    public PatientMonitoringRecord deleteRecord(Long id, String accountId) {
        if (id == null) return null;

        PatientMonitoringRecord record = recordRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (record == null) {
            log.warn("No matching undeleted record found for id={}, cannot delete.", id);
            return null;
        }
        markRecordAsDeleted(record, accountId, TimeUtils.getNowUtc());
        return recordRepo.save(record);
    }

    public void markRecordAsDeleted(PatientMonitoringRecord record, String accountId, LocalDateTime modifiedAt) {
        if (record == null) return;

        record.setIsDeleted(true);
        record.setDeleteReason(DELETED_REASON_DELETED);
        record.setDeletedBy(accountId);
        record.setDeletedAt(modifiedAt);
        recordRepo.save(record);
    }

    private boolean validateBalanceSumParams(
        LocalDateTime startTime, LocalDateTime endTime, String modifiedBy,
        Long pid, String deptId, String paramCode, ValueMetaPB valueMeta,
        Map<LocalDateTime, GenericValuePB> timeValMap
    ) {
        if (startTime == null || endTime == null || modifiedBy == null ||
            pid == null || deptId == null || paramCode == null ||
            valueMeta == null || timeValMap == null
        ) {
            log.warn("Invalid parameters for balance sum records: {} - {} " +
                "modifiedBy={}, pid={}, deptId={}, paramCode={}, " +
                "valueMeta={}, timeValMap={}, from stack {}",
                startTime, endTime, modifiedBy, pid, deptId, paramCode, valueMeta,
                timeValMap, Arrays.toString(Thread.currentThread().getStackTrace())
            );
            return false;
        }

        if (endTime.isBefore(startTime)) {
            log.warn("End time {} is before start time {}, from stack {}",
                endTime, startTime, Arrays.toString(Thread.currentThread().getStackTrace())
            );
            return false;
        }

        for (Map.Entry<LocalDateTime, GenericValuePB> entry : timeValMap.entrySet()) {
            LocalDateTime effectiveTime = entry.getKey();
            if (effectiveTime.isBefore(startTime) || !effectiveTime.isBefore(endTime)) {
                log.warn("Effective time {} is out of range {} - {}, from stack {}",
                    effectiveTime, startTime, endTime,
                    Arrays.toString(Thread.currentThread().getStackTrace())
                );
                return false;
            }
        }
        return true;
    }

    private PatientMonitoringRecord newMonitoringRecord(
        Long pid, String deptId, String paramCode, LocalDateTime effectiveTime,
        ValueMetaPB valueMeta, GenericValuePB value, String modifiedBy, LocalDateTime modifiedAt
    ) {
        String paramValue = ProtoUtils.encodeMonitoringValue(
            MonitoringValuePB.newBuilder().setValue(value).build());
        String paramValueStr = ValueMetaUtils.extractAndFormatParamValue(value, valueMeta);

        return PatientMonitoringRecord.builder()
            .pid(pid)
            .deptId(deptId)
            .monitoringParamCode(paramCode)
            .effectiveTime(effectiveTime)
            .paramValue(paramValue)
            .paramValueStr(paramValueStr)
            .unit(valueMeta.getUnit())
            .source("")
            .modifiedBy(modifiedBy)
            .modifiedAt(modifiedAt)
            .isDeleted(false)
            .build();
    }

    private boolean isValidNewRecord(PatientMonitoringRecord record) {
        boolean valid = record != null &&
            record.getIsDeleted() != null && !record.getIsDeleted() &&
            record.getModifiedAt() != null &&
            record.getModifiedBy() != null &&
            record.getEffectiveTime() != null &&
            record.getPid() != null &&
            record.getMonitoringParamCode() != null &&
            (record.getParamValue() != null || record.getParamValueStr() != null);
        if (!valid) {
            log.warn("Invalid monitoring record: {} from stack {}",
                record, Arrays.toString(Thread.currentThread().getStackTrace())
            );
        }
        return valid;
    }

    private boolean diff(PatientMonitoringRecord existingRecord, PatientMonitoringRecord newRecord) {
        return !Objects.equals(existingRecord.getParamValue(), newRecord.getParamValue())
            || !Objects.equals(existingRecord.getParamValueStr(), newRecord.getParamValueStr())
            || !Objects.equals(existingRecord.getUnit(), newRecord.getUnit())
            || !Objects.equals(existingRecord.getDeviceId(), newRecord.getDeviceId())
            || !Objects.equals(existingRecord.getSource(), newRecord.getSource())
            || !Objects.equals(existingRecord.getNote(), newRecord.getNote())
            || !Objects.equals(existingRecord.getStatus(), newRecord.getStatus());
    }

    private PatientMonitoringRecord mapRow(ResultSet rs) throws SQLException {
        return PatientMonitoringRecord.builder()
            .id(rs.getLong("id"))
            .pid(rs.getLong("pid"))
            .deptId(rs.getString("dept_id"))
            .monitoringParamCode(rs.getString("monitoring_param_code"))
            .effectiveTime(rs.getTimestamp("effective_time").toLocalDateTime())
            .paramValue(rs.getString("param_value"))
            .paramValueStr(rs.getString("param_value_str"))
            .unit(rs.getString("unit"))
            .deviceId(rs.getObject("device_id", Integer.class))
            .source(rs.getString("source"))
            .note(rs.getString("note"))
            .status(rs.getString("status"))
            .modifiedBy(rs.getString("modified_by"))
            .modifiedAt(rs.getTimestamp("modified_at").toLocalDateTime())
            .isDeleted(rs.getBoolean("is_deleted"))
            .deleteReason(rs.getString("delete_reason"))
            .deletedBy(rs.getString("deleted_by"))
            .deletedAt(rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toLocalDateTime() : null)
            .build();
    }

    private void updateIsDeleted(Connection conn, PatientMonitoringRecord record) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            UPDATE patient_monitoring_records
            SET is_deleted = TRUE, delete_reason = ?, deleted_by = ?, deleted_at = ?
            WHERE id = ?
        """)) {
            ps.setString(1, record.getDeleteReason());
            ps.setString(2, record.getDeletedBy());
            ps.setTimestamp(3, Timestamp.valueOf(record.getDeletedAt()));
            ps.setLong(4, record.getId());
            ps.executeUpdate();
        }
    }

    private void insertRecord(Connection conn, PatientMonitoringRecord record, boolean batchMode) throws SQLException {
        String sql = """
            INSERT INTO patient_monitoring_records (
                pid, dept_id, monitoring_param_code, effective_time,
                param_value, param_value_str, unit, device_id,
                source, note, status, modified_by, modified_at, is_deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
        """;

        try (PreparedStatement ps = batchMode ?
            conn.prepareStatement(sql) :
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
        ) {
            ps.setLong(1, record.getPid());
            ps.setString(2, record.getDeptId());
            ps.setString(3, record.getMonitoringParamCode());
            ps.setTimestamp(4, Timestamp.valueOf(record.getEffectiveTime()));
            ps.setString(5, record.getParamValue());
            ps.setString(6, record.getParamValueStr());
            ps.setString(7, record.getUnit());
            if (record.getDeviceId() != null) ps.setInt(8, record.getDeviceId()); else ps.setNull(8, Types.INTEGER);
            ps.setString(9, record.getSource());
            ps.setString(10, record.getNote());
            ps.setString(11, record.getStatus());
            ps.setString(12, record.getModifiedBy());
            ps.setTimestamp(13, Timestamp.valueOf(record.getModifiedAt()));
            ps.executeUpdate();

            if (!batchMode) {
                // 获取自动生成的主键
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        long generatedId = keys.getLong(1);
                        record.setId(generatedId);
                    } else {
                        throw new SQLException("插入记录失败，未能获取生成的ID");
                    }
                }
            }
        }
    }

    public void deleteRecords(
        Long pid, String paramCode, LocalDateTime startTime, LocalDateTime endTime, String modifiedBy
    ) {
        if (pid == null || paramCode == null || startTime == null || endTime == null || modifiedBy == null) {
            log.warn("Invalid parameters for deleting records: pid={}, paramCode={}, startTime={}, endTime={}, modifiedBy={}, from stack {}",
                pid, paramCode, startTime, endTime, modifiedBy,
                Arrays.toString(Thread.currentThread().getStackTrace())
            );
            return;
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            
            String sql = """
                UPDATE patient_monitoring_records 
                SET is_deleted = true, delete_reason = ?, deleted_at = ?, deleted_by = ?
                WHERE pid = ? AND monitoring_param_code = ? AND effective_time >= ? AND effective_time <= ? AND is_deleted = false
            """;
            
            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, DELETED_REASON_DELETED);
                ps.setTimestamp(2, Timestamp.valueOf(nowUtc));
                ps.setString(3, modifiedBy);
                ps.setLong(4, pid);
                ps.setString(5, paramCode);
                ps.setTimestamp(6, Timestamp.valueOf(startTime));
                ps.setTimestamp(7, Timestamp.valueOf(endTime));
                
                int affectedRows = ps.executeUpdate();
                log.info("Deleted {} records for pid={}, paramCode={}, time range {} - {}", 
                    affectedRows, pid, paramCode, startTime, endTime);
            }
            
            conn.commit();
        } catch (Exception e) {
            log.error("Failed to delete records via JDBC: pid={}, paramCode={}, startTime={}, endTime={}", 
                pid, paramCode, startTime, endTime, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveDailyStats(
        LocalDateTime startTime, LocalDateTime endTime, String modifiedBy,
        Long pid, String deptId, String paramCode, ValueMetaPB valueMeta,
        Map<LocalDateTime, Float> dailyMap
    ) {
        if (!validateDailyStatsParams(startTime, endTime, modifiedBy, pid, deptId, paramCode, valueMeta, dailyMap)) return;

        // 统计时间范围内的现有记录
        Map<LocalDateTime, PatientMonitoringRecordStatsDaily> existingStats = dailyStatsRepo
            .findByPidAndParamCodeAndTimeBetween(pid, paramCode, startTime, endTime)
            .stream()
            .collect(Collectors.toMap(
                PatientMonitoringRecordStatsDaily::getEffectiveTime,
                stats -> stats,
                (stats1, stats2) -> stats1  // 如果有重复，保留第一个
            ));
        
        // 更新记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<PatientMonitoringRecordStatsDaily> statsToDelete = new ArrayList<>();
        List<PatientMonitoringRecordStatsDaily> statsToAdd = new ArrayList<>();
        for (Map.Entry<LocalDateTime, Float> entry : dailyMap.entrySet()) {
            LocalDateTime effectiveTime = entry.getKey();
            Float value = entry.getValue();
            PatientMonitoringRecordStatsDaily newStat = newDailyStats(
                pid, deptId, paramCode, effectiveTime, valueMeta, value, modifiedBy, nowUtc
            );

            PatientMonitoringRecordStatsDaily existingStat = existingStats.get(effectiveTime);
            if (existingStat == null) {
                // 新增记录
                statsToAdd.add(newStat);
                continue;
            }

            // 判断是否真的需要更新
            boolean toUpdate = !Objects.equals(existingStat.getParamValue(), newStat.getParamValue())
                || !Objects.equals(existingStat.getParamValueStr(), newStat.getParamValueStr())
                || !Objects.equals(existingStat.getUnit(), newStat.getUnit())
                || !Objects.equals(existingStat.getNote(), newStat.getNote());
            if (!toUpdate) continue;

            // 逻辑删除旧记录，保存新记录
            existingStat.setIsDeleted(true);
            existingStat.setDeleteReason(DELETED_REASON_UPDATED);
            existingStat.setDeletedAt(nowUtc);
            existingStat.setDeletedBy(modifiedBy);
            statsToDelete.add(existingStat);
            statsToAdd.add(newStat);
        }

        // 删除不在新记录中的旧记录
        Set<LocalDateTime> timesToDelete = new HashSet<>(existingStats.keySet());
        timesToDelete.removeAll(dailyMap.keySet());
        for (LocalDateTime time : timesToDelete) {
            PatientMonitoringRecordStatsDaily oldStat = existingStats.get(time);
            if (oldStat == null) continue;
            oldStat.setIsDeleted(true);
            oldStat.setDeleteReason(DELETED_REASON_DELETED);
            oldStat.setDeletedAt(nowUtc);
            oldStat.setDeletedBy(modifiedBy);
            statsToDelete.add(oldStat);
        }

        if (!statsToDelete.isEmpty()) {
            try {
                dailyStatsRepo.saveAll(statsToDelete);
            } catch (Exception e) {
                log.error("Failed to save daily stats: {}", e.getMessage(), e);
                throw e;
            }
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (PatientMonitoringRecordStatsDaily add : statsToDelete) {
                updateDailyStatsIsDeleted(conn, add);
            }
            conn.commit();

            conn.setAutoCommit(false); // 重新开始新事务
            for (PatientMonitoringRecordStatsDaily add : statsToAdd) {
                insertDailyStats(conn, add, true);
            }
            conn.commit();
        } catch (Exception e) {
            log.error("Failed to save daily stats: {}", e.getMessage(), e);
        }
    }

    private boolean validateDailyStatsParams(
        LocalDateTime startTime, LocalDateTime endTime, String modifiedBy,
        Long pid, String deptId, String paramCode, ValueMetaPB valueMeta,
        Map<LocalDateTime, Float> dailyMap
    ) {
        if (startTime == null || endTime == null || modifiedBy == null ||
            pid == null || deptId == null || paramCode == null || valueMeta == null ||
            dailyMap == null) {
            log.warn("Invalid daily stats params: {} - {} - {} - {} - {} - {} - {} - {}, from stack {}",
                startTime, endTime, modifiedBy, pid, deptId, paramCode, valueMeta, dailyMap,
                Arrays.toString(Thread.currentThread().getStackTrace())
            );
            return false;
        }

        if (endTime.isBefore(startTime)) {
            log.warn("End time {} is before start time {}, from stack {}",
                endTime, startTime, Arrays.toString(Thread.currentThread().getStackTrace())
            );
            return false;
        }

        for (LocalDateTime time : dailyMap.keySet()) {
            if (time.isBefore(startTime) || !time.isBefore(endTime)) {
                log.warn("Daily stats time {} is out of range {} - {}, from stack {}",
                    time, startTime, endTime,
                    Arrays.toString(Thread.currentThread().getStackTrace())
                );
                return false;
            }
        }
        for (Map.Entry<LocalDateTime, Float> entry : dailyMap.entrySet()) {
            LocalDateTime time = entry.getKey();
            if (time.isBefore(startTime) || !time.isBefore(endTime)) {
                log.warn("Daily stats time {} is out of range {} - {}, from stack {}",
                    time, startTime, endTime,
                    Arrays.toString(Thread.currentThread().getStackTrace())
                );
                return false;
            }

            Float value = entry.getValue();
            if (value == null || value.isNaN() || value.isInfinite()) {
                log.warn("Invalid float value {} at time {}, skipping", value, time);
                return false;
            }
        }
        return true;
    }

    private PatientMonitoringRecordStatsDaily newDailyStats(
        Long pid, String deptId, String paramCode, LocalDateTime effectiveTime, 
        ValueMetaPB valueMeta, Float value, String modifiedBy, LocalDateTime modifiedAt
    ) {
        GenericValuePB genericValue = ValueMetaUtils.fromFloat(valueMeta, value);
        String paramValue = ProtoUtils.encodeGenericValue(genericValue);
        String paramValueStr = ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta);

        return PatientMonitoringRecordStatsDaily.builder()
            .pid(pid)
            .deptId(deptId)
            .monitoringParamCode(paramCode)
            .effectiveTime(effectiveTime)
            .paramValue(paramValue)
            .paramValueStr(paramValueStr)
            .unit(valueMeta.getUnit())
            .modifiedBy(modifiedBy)
            .modifiedAt(modifiedAt)
            .isDeleted(false)
            .build();
    }

    private void updateDailyStatsIsDeleted(Connection conn, PatientMonitoringRecordStatsDaily record) throws SQLException {
        String sql = """
            UPDATE patient_monitoring_record_stats_daily
            SET is_deleted = true,
                deleted_by = ?,
                deleted_at = ?,
                delete_reason = ?
            WHERE id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.getDeletedBy());
            ps.setTimestamp(2, Timestamp.valueOf(record.getDeletedAt()));
            ps.setString(3, record.getDeleteReason());
            ps.setLong(4, record.getId());

            int affected = ps.executeUpdate();
            if (affected != 1) {
                throw new SQLException("updateDailyStatsIsDeleted failed, affected rows: " + affected);
            }
        }
    }

    private void insertDailyStats(Connection conn, PatientMonitoringRecordStatsDaily record, boolean batchMode) throws SQLException {
        String sql = """
            INSERT INTO patient_monitoring_record_stats_daily (
                pid, dept_id, monitoring_param_code, effective_time,
                param_value, param_value_str, unit, note,
                status, modified_by, modified_at, is_deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
        """;

        try (PreparedStatement ps = batchMode ?
            conn.prepareStatement(sql) :
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
        ) {
            ps.setLong(1, record.getPid());
            ps.setString(2, record.getDeptId());
            ps.setString(3, record.getMonitoringParamCode());
            ps.setTimestamp(4, Timestamp.valueOf(record.getEffectiveTime()));
            ps.setString(5, record.getParamValue());
            ps.setString(6, record.getParamValueStr());
            ps.setString(7, record.getUnit());
            ps.setString(8, record.getNote());
            ps.setString(9, record.getStatus());
            ps.setString(10, record.getModifiedBy());
            ps.setTimestamp(11, Timestamp.valueOf(record.getModifiedAt()));

            ps.executeUpdate();

            if (!batchMode) {
                // 获取自动生成的主键
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        record.setId(keys.getLong(1));
                    } else {
                        throw new SQLException("insertDailyStats failed: unable to retrieve generated ID");
                    }
                }
            }
        }
    }

    private final PatientMonitoringRecordRepository recordRepo;
    private final PatientMonitoringRecordStatsDailyRepository dailyStatsRepo;

    @Autowired
    private DataSource dataSource;

    private static final int SAVE_RECORD_MAX_RETRIES = 3;
    private static final long SAVE_RECORD_RETRY_BASE_DELAY_MS = 50;

    private boolean isDeadlockOrSerializationFailure(SQLException e) {
        SQLException current = e;
        while (current != null) {
            String state = current.getSQLState();
            if ("40P01".equals(state) || "40001".equals(state)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause instanceof SQLException) {
                current = (SQLException) cause;
            } else {
                current = null;
            }
        }
        return false;
    }

    private void backoffSleep(int attempt) {
        long delayMs = SAVE_RECORD_RETRY_BASE_DELAY_MS * attempt;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
