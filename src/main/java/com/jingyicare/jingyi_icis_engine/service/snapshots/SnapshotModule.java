package com.jingyicare.jingyi_icis_engine.service.snapshots;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindow;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

/** 模块只负责自身 PB 字段；锁、事务和整行持久化由 SnapshotService 负责。 */
public interface SnapshotModule {
    enum Kind { NURSING_RECORD, SCORE_GROUP }

    /** refresh 动作的上下文 */
    record Context(
        Long pid, String deptId, LocalDate statisticsDate,
        StatisticsDayWindow window, LocalDateTime snapshotAt
    ) {}

    Kind kind();

    String lastModifiedAt(PatientSnapshotPB snapshot);

    String snapshotAt(PatientSnapshotPB snapshot);

    /** 设置最后修改时间 */
    void markModified(PatientSnapshotPB.Builder snapshot, String modifiedAt);

    /** 替换 本模块数据 并设置 本模块sa；空结果也代表一次成功刷新。 */
    void refresh(Context context, PatientSnapshotPB.Builder snapshot);

    /** 持续计算的模块可覆盖此方法，增加随时间推移的刷新条件。 */
    default boolean needsRefresh(Context context, PatientSnapshotPB snapshot) {
        LocalDateTime savedAt = TimeUtils.fromIso8601String(snapshotAt(snapshot), "UTC");
        if (savedAt == null) return true;
        String modified = lastModifiedAt(snapshot);
        if (StrUtils.isBlank(modified)) return false;
        LocalDateTime modifiedAt = TimeUtils.fromIso8601String(modified, "UTC");
        // 秒精度下相等时保守刷新，避免遗漏同一秒内发生的修改。
        return modifiedAt == null || !modifiedAt.isBefore(savedAt);
    }
}
