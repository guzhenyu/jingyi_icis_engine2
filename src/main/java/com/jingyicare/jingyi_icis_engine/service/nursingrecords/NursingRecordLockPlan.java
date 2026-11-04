package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotChangePlan;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotChangePlan.Change;

/**
 * 护理写入的完整锁资源：旧、新时间对应的护理锁，以及受影响统计日的快照锁。
 * 本类只组装计划和比较记录；JDBC 预读、锁后复查及事务执行由 Service 编排。
 * records 是锁后复查成功时填充的记录容器，业务回调使用这批最新记录。
 */
record NursingRecordLockPlan(
    Map<Long, Change> recordChanges, SnapshotChangePlan snapshotPlan,
    List<String> resourceKeys, List<NursingRecord> records
) {
    NursingRecordLockPlan {
        recordChanges = Map.copyOf(recordChanges);
        resourceKeys = List.copyOf(resourceKeys);
    }

    /**
     fallbackKeys 处理的是：第一次预读时记录还在，正式生成锁计划时，记录已被另一个请求删除。
     例如，两个请求 A、B 同时删除护理记录 id=100，它属于患者 42，有效时间是 10:00：
        1. A 第一次预读，查到记录，保存对应的护理锁键作为 fallbackKeys。
        2. B 先取得锁，完成删除并提交事务。
        3. A 开始生成锁计划，再次查询活动记录，结果已经为空。此时没有记录，也就算不出护理锁和快照锁。
        4. A 使用先前保存的 fallbackKeys 取得锁，锁后复查确认记录确实不存在，最终按“重复删除”的语义返回 OK。
     */
    static NursingRecordLockPlan create(
        List<NursingRecord> records, SnapshotChangePlan snapshotPlan, List<String> fallbackKeys
    ) {
        return new NursingRecordLockPlan(recordChanges(records), snapshotPlan,
            resourceKeys(snapshotPlan, fallbackKeys), new ArrayList<>());
    }

    /** 新增使用同样的资源计算；旧记录全部消失时保留兜底锁，以便在锁内确认。 */
    static List<String> resourceKeys(SnapshotChangePlan snapshotPlan, List<String> fallbackKeys) {
        List<String> keys = new ArrayList<>(snapshotPlan.changes().stream()
            .map(change -> recordKey(change.pid(), change.effectiveTime())).toList());
        if (keys.isEmpty()) keys.addAll(fallbackKeys);
        keys.addAll(snapshotPlan.resourceKeys());
        return keys.stream().distinct().sorted().toList();
    }

    /** 更新时同时包含旧、新时间；删除和审核只包含现有记录的时间。 */
    static List<Change> affectedChanges(
        List<NursingRecord> records, Function<NursingRecord, LocalDateTime> targetTimeResolver
    ) {
        List<Change> changes = new ArrayList<>(recordChanges(records).values());
        if (targetTimeResolver != null) {
            for (NursingRecord record : records) {
                changes.add(new Change(record.getPatientId(), targetTimeResolver.apply(record)));
            }
        }
        return changes;
    }

    static List<String> recordKeys(List<NursingRecord> records) {
        return records.stream().map(record -> recordKey(
            record.getPatientId(), record.getEffectiveTime())).toList();
    }

    static Map<Long, Change> recordChanges(List<NursingRecord> records) {
        return records.stream().collect(Collectors.toMap(NursingRecord::getId,
            record -> new Change(record.getPatientId(), record.getEffectiveTime())));
    }

    boolean matchesRecords(List<NursingRecord> current) {
        // 复查保留完整时间精度，不能只比较截断到分钟的护理锁键。
        return recordChanges.equals(recordChanges(current));
    }

    private static String recordKey(Long pid, LocalDateTime effectiveTime) {
        return LockKeys.nursingRecord(pid, effectiveTime.truncatedTo(ChronoUnit.MINUTES));
    }
}
