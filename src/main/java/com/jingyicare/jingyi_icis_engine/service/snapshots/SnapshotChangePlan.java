package com.jingyicare.jingyi_icis_engine.service.snapshots;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;

/** 快照变更计划只保存不可变预读结果和锁资源，不负责数据库读取或事务执行。 */
// departments: pid -> deptId
// statisticsDates: effectiveTime 对应的 统计日列表
public record SnapshotChangePlan(
    Map<Long, String> departments, Map<Change, List<LocalDate>> statisticsDates
) {
    public SnapshotChangePlan {
        departments = Map.copyOf(departments);
        Map<Change, List<LocalDate>> dates = new LinkedHashMap<>();
        statisticsDates.forEach((change, values) -> dates.put(change, List.copyOf(values)));
        statisticsDates = Map.copyOf(dates);
    }

    public Set<Change> changes() {
        return statisticsDates.keySet();
    }

    public List<String> resourceKeys() {
        return statisticsDates.entrySet().stream()
            .flatMap(entry -> entry.getValue().stream()
                .map(date -> LockKeys.patientSnapshot(entry.getKey().pid(), date)))
            .distinct().sorted().toList();
    }

    /** 一个业务变更点；更新有效时间时同时提供旧、新两个点。 */
    public record Change(Long pid, LocalDateTime effectiveTime) {
        public Change {
            if (pid == null || pid <= 0 || effectiveTime == null) {
                throw new IllegalArgumentException("Patient and effective time are required");
            }
        }
    }
}
