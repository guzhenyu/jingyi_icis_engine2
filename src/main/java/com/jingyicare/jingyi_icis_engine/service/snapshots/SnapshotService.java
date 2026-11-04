package com.jingyicare.jingyi_icis_engine.service.snapshots;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.entity.snapshots.PatientSnapshot;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.snapshots.PatientSnapshotRepository;
import com.jingyicare.jingyi_icis_engine.repository.snapshots.SnapshotJdbc;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindow;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotChangePlan.Change;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class SnapshotService {
    public SnapshotService(
        ConfigProtoService protoService, PatientRecordRepository patientRepo,
        PatientSnapshotRepository snapshotRepo, StatisticsDayWindowResolver windowResolver,
        LockExecutor lockExecutor, JdbcTemplate jdbcTemplate, List<SnapshotModule> modules
    ) {
        this.protoService = protoService;
        this.patientRepo = patientRepo;
        this.snapshotRepo = snapshotRepo;
        this.windowResolver = windowResolver;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
        this.zoneId = protoService.getZoneId();
        this.modules = new EnumMap<>(SnapshotModule.Kind.class);
        for (SnapshotModule module : modules) {
            if (this.modules.put(module.kind(), module) != null) {
                throw new IllegalArgumentException("Duplicate snapshot module: " + module.kind());
            }
        }
        if (this.modules.isEmpty()) {
            throw new IllegalArgumentException("At least one snapshot module is required");
        }
    }

    /** 刷新并返回去重、升序的统计日快照；由 LockExecutor 统一建立事务。 */
    public Pair<ReturnCode, List<PatientSnapshotPB>> get(
        Long pid, List<LocalDate> statDates, boolean forceRefresh
    ) {
        // 参数验证，避免无效锁计划和空列表导致的事务提交。
        if (pid == null || pid <= 0 || statDates == null
            || statDates.stream().anyMatch(Objects::isNull)
        ) {
            log.warn("Invalid parameters for get snapshots, pid={}, statDates={}", pid, statDates);
            return response(StatusCode.INVALID_PARAM_VALUE, List.of());
        }
        List<LocalDate> dates = statDates.stream().distinct().sorted().toList();
        if (dates.isEmpty()) return response(StatusCode.OK, List.of());

        // 取得锁后再通过 JDBC 读取，避免 JPA 一级缓存掩盖计划变化。
        List<String> keys = dates.stream()
            .map(date -> LockKeys.patientSnapshot(pid, date)).toList();
        try {
            return lockExecutor.execute(keys, () -> getLocked(pid, dates, forceRefresh));
        } catch (RuntimeException e) {
            // 必须在 LockExecutor 完成回滚后转成错误响应，不能提交部分刷新结果。
            log.error("Failed to refresh patient snapshots, pid={}", pid, e);
            return response(StatusCode.INTERNAL_EXCEPTION, List.of());
        }
    }

    /** 从事务外预读锁计划；缺失患者由业务入口返回对应错误，不在此创建快照。 */
    public SnapshotChangePlan planChanges(Collection<Change> changes) {
        List<Change> points = changes.stream().distinct()
            .sorted(Comparator.comparing(Change::pid).thenComparing(Change::effectiveTime))
            .toList();
        List<Long> pids = points.stream().map(Change::pid).distinct().toList();
        Map<Long, String> departments = SnapshotJdbc.findPatientDepartments(jdbcTemplate, pids);
        Map<String, List<BalanceStatsShift>> shifts = SnapshotJdbc.findActiveBalanceStatsShifts(
            jdbcTemplate, departments.values().stream().distinct().toList());
        Map<Change, List<LocalDate>> dates = new LinkedHashMap<>();
        for (Change change : points) {
            String deptId = departments.get(change.pid());
            dates.put(change, deptId == null ? List.of()
                : windowResolver.resolveStatisticDatesFromShifts(change.effectiveTime(), zoneId,
                    shifts.getOrDefault(deptId, List.of())));
        }
        return new SnapshotChangePlan(points, departments, dates);
    }

    /** 取得全部锁后再次通过 JDBC 读取，避免 JPA 一级缓存掩盖计划变化。 */
    public boolean isPlanCurrent(SnapshotChangePlan plan) {
        // 当前使用的 record、List、Map、日期和字符串都支持内容比较
        return plan.equals(planChanges(plan.changes()));
    }

    /**
     * 供护理及后续模块在业务数据实际变更后更新 lma，changed 仅包含实际变化的点。
     * 调用方必须已通过 LockExecutor 取得 plan 中的快照锁，并处于同一事务。
     * 不添加 @Transactional，也不再次调用 LockExecutor：事务由调用方统一管理，
     * 业务写入和快照标记必须一起提交或回滚，不能通过独立事务提前提交标记。
     */
    public void markModified(
        SnapshotModule.Kind kind, SnapshotChangePlan plan, Collection<Change> changed,
        LocalDateTime modifiedAt
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Snapshot changes require the caller's transaction");
        }
        SnapshotModule module = Objects.requireNonNull(modules.get(kind), "snapshot module");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Map<Long, TreeSet<LocalDate>> datesByPatient = new TreeMap<>();
        for (Change change : changed) {
            List<LocalDate> dates = plan.statisticsDates().get(change);
            if (dates == null) {
                throw new IllegalArgumentException("Change is outside the lock plan");
            }
            datesByPatient.computeIfAbsent(change.pid(), ignored -> new TreeSet<>()).addAll(dates);
        }
        String modifiedIso = TimeUtils.toIso8601StringWithSeconds(modifiedAt, "UTC");
        List<PatientSnapshot> updates = new ArrayList<>();
        datesByPatient.forEach((pid, dates) -> {
            if (dates.isEmpty()) return;
            Map<LocalDate, PatientSnapshot> existing = loadSnapshots(pid, dates);
            for (LocalDate date : dates) {
                PatientSnapshot row = existing.getOrDefault(date, newSnapshot(pid, date));
                PatientSnapshotPB pb = ProtoUtils.decodePatientSnapshot(row.getPayload());
                boolean valid = matchesIdentity(pb, pid, date);
                PatientSnapshotPB.Builder builder = valid ? pb.toBuilder() : newPayload(pid, date);
                if (!valid) row.setSnapshotAt(null);
                module.markModified(builder, modifiedIso);
                row.setPayload(ProtoUtils.encodePatientSnapshot(builder.build()));
                row.setLastModifiedAt(modifiedAt);
                updates.add(row);
            }
        });
        if (!updates.isEmpty()) snapshotRepo.saveAll(updates);
    }

    private Pair<ReturnCode, List<PatientSnapshotPB>> getLocked(
        Long pid, List<LocalDate> dates, boolean forceRefresh
    ) {
        LocalDateTime snapshotAt = TimeUtils.getNowUtc();
        PatientRecord patient = patientRepo.findById(pid).orElse(null);
        if (patient == null || StrUtils.isBlank(patient.getDeptId())) {
            return response(StatusCode.PATIENT_NOT_FOUND, List.of());
        }
        List<StatisticsDayWindow> windows = windowResolver.resolveInternal(dates.stream()
            .map(date -> TimeUtils.getDayMidnightUtc(date, ZoneId.of(zoneId))).toList(),
            zoneId, patient.getDeptId());
        Map<LocalDate, PatientSnapshot> existing = loadSnapshots(pid, dates);
        List<PatientSnapshot> updates = new ArrayList<>();
        List<PatientSnapshotPB> result = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            StatisticsDayWindow window = windows.get(i);
            PatientSnapshot row = existing.getOrDefault(date, newSnapshot(pid, date));
            PatientSnapshotPB pb = ProtoUtils.decodePatientSnapshot(row.getPayload());
            boolean valid = matchesIdentity(pb, pid, date);
            boolean refreshAll = forceRefresh || row.getSnapshotAt() == null || !valid
                || !matchesWindow(pb, window);
            // 身份正确时保留其他模块和未知字段；不复用错属患者或日期的内容。
            PatientSnapshotPB.Builder builder = valid ? pb.toBuilder() : newPayload(pid, date);
            SnapshotModule.Context context = new SnapshotModule.Context(
                pid, patient.getDeptId(), date, window, snapshotAt);
            boolean refreshed = false;
            for (SnapshotModule module : modules.values()) {
                if (refreshAll || module.needsRefresh(context, pb)) {
                    module.refresh(context, builder);
                    refreshed = true;
                }
            }
            if (refreshed) {
                updateWindow(builder.setPid(pid).setStatisticsDate(date.toString()), window);
                row.setPayload(ProtoUtils.encodePatientSnapshot(builder.build()));
                row.setSnapshotAt(snapshotAt);
                updates.add(row);
            }
            result.add(builder.build());
        }
        if (!updates.isEmpty()) snapshotRepo.saveAll(updates);
        return response(StatusCode.OK, result);
    }

    private Map<LocalDate, PatientSnapshot> loadSnapshots(Long pid, Collection<LocalDate> dates) {
        return snapshotRepo.findByPidAndStatisticsDateIn(pid, dates).stream()
            .collect(Collectors.toMap(PatientSnapshot::getStatisticsDate, row -> row));
    }

    private PatientSnapshot newSnapshot(Long pid, LocalDate date) {
        return PatientSnapshot.builder().pid(pid).statisticsDate(date).build();
    }

    private PatientSnapshotPB.Builder newPayload(Long pid, LocalDate date) {
        return PatientSnapshotPB.newBuilder().setPid(pid).setStatisticsDate(date.toString());
    }

    private boolean matchesIdentity(PatientSnapshotPB pb, Long pid, LocalDate date) {
        return pb != null && pb.getPid() == pid && pb.getStatisticsDate().equals(date.toString());
    }

    private boolean matchesWindow(PatientSnapshotPB pb, StatisticsDayWindow window) {
        return pb != null
            && matchesTime(pb.getMonitoringStartIso8601(), window.monitoringStartUtc())
            && matchesTime(pb.getMonitoringEndIso8601(), window.monitoringEndUtc())
            && matchesTime(pb.getBalanceStartIso8601(), window.balanceStartUtc())
            && matchesTime(pb.getBalanceEndIso8601(), window.balanceEndUtc());
    }

    private boolean matchesTime(String iso, LocalDateTime utc) {
        return utc.equals(TimeUtils.fromIso8601String(iso, "UTC"));
    }

    private void updateWindow(PatientSnapshotPB.Builder builder, StatisticsDayWindow window) {
        builder.setMonitoringStartIso8601(
            TimeUtils.toIso8601StringWithSeconds(window.monitoringStartUtc(), "UTC"));
        builder.setMonitoringEndIso8601(
            TimeUtils.toIso8601StringWithSeconds(window.monitoringEndUtc(), "UTC"));
        builder.setBalanceStartIso8601(
            TimeUtils.toIso8601StringWithSeconds(window.balanceStartUtc(), "UTC"));
        builder.setBalanceEndIso8601(
            TimeUtils.toIso8601StringWithSeconds(window.balanceEndUtc(), "UTC"));
    }

    private Pair<ReturnCode, List<PatientSnapshotPB>> response(
        StatusCode code, List<PatientSnapshotPB> snapshots
    ) {
        return new Pair<>(protoService.getReturnCode(code), List.copyOf(snapshots));
    }

    private final ConfigProtoService protoService;
    private final PatientRecordRepository patientRepo;
    private final PatientSnapshotRepository snapshotRepo;
    private final StatisticsDayWindowResolver windowResolver;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final String zoneId;
    private final Map<SnapshotModule.Kind, SnapshotModule> modules;
}
