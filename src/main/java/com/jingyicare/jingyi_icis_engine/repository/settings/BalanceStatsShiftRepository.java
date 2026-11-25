package com.jingyicare.jingyi_icis_engine.repository.settings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.settings.BalanceStatsShift;

public interface BalanceStatsShiftRepository extends JpaRepository<BalanceStatsShift, Long> {
    List<BalanceStatsShift> findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeAsc(String deptId);
    Optional<BalanceStatsShift> findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
        String deptId, LocalDateTime effectiveTime
    );
    Optional<BalanceStatsShift> findByDeptIdAndEffectiveTimeAndIsDeletedFalse(String deptId, LocalDateTime effectiveTime);
    Optional<BalanceStatsShift> findByIdAndIsDeletedFalse(Long id);
    Optional<BalanceStatsShift> findByIdAndDeptIdAndIsDeletedFalse(Long id, String deptId);
}
