package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotChangePlan;

/** 只组装旧、新业务锁和快照锁，比较决定锁范围的字段；不访问数据库。 */
record ScoreLockPlan(
    PatientScore plannedRecord, Long pid, String deptId,
    String code, LocalDateTime targetTime, String patientScoreStr,
    String paramCode, SnapshotChangePlan snapshotPlan, List<String> resourceKeys,
    String accountId, String accountName
) {
    static ScoreLockPlan create(
        PatientScore record, Long pid, String deptId,
        String code, LocalDateTime targetTime, String patientScoreStr,
        String paramCode, SnapshotChangePlan snapshotPlan,
        String accountId, String accountName
    ) {
        List<String> keys = new ArrayList<>(snapshotPlan.resourceKeys());
        if (record != null) {
            addKeys(record.getPid(), record.getScoreGroupCode(),
                record.getEffectiveTime(), paramCode, keys /* OUT */);
        }
        if (targetTime != null) {
            addKeys(pid, code, targetTime, paramCode, keys /* OUT */);
        }

        return new ScoreLockPlan(record, pid, deptId,
            code, targetTime, patientScoreStr,
            paramCode, snapshotPlan, keys.stream().distinct().sorted().toList(),
            accountId, accountName);
    }

    private static void addKeys(
        Long pid, String code, LocalDateTime time, String paramCode, List<String> keys /* OUT */
    ) {
        keys.add(LockKeys.patientScore(pid, code, time.truncatedTo(ChronoUnit.MINUTES)));
        if (paramCode != null) keys.add(LockKeys.patientMonitoringRecord(pid, paramCode, time));
    }

    boolean matches(PatientScore current) {
        if (plannedRecord == null || current == null) return plannedRecord == current;
        // 旧记录可能含秒；复查保留完整精度，不能仅比较分钟级评分锁键。
        return Objects.equals(plannedRecord.getId(), current.getId())
            && Objects.equals(plannedRecord.getPid(), current.getPid())
            && Objects.equals(plannedRecord.getScoreGroupCode(), current.getScoreGroupCode())
            && Objects.equals(plannedRecord.getEffectiveTime(), current.getEffectiveTime());
    }
}
