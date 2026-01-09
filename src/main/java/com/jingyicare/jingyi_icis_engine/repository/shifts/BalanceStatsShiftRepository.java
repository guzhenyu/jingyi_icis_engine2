package com.jingyicare.jingyi_icis_engine.repository.shifts;

import java.time.LocalDateTime;
import java.util.*;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceStatsShiftRepository extends JpaRepository<BalanceStatsShift, Long> {
    List<BalanceStatsShift> findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(String deptId);
    List<BalanceStatsShift> findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeAsc(String deptId);
    Optional<BalanceStatsShift> findByDeptIdAndEffectiveTimeAndIsDeletedFalse(String deptId, LocalDateTime effectiveTime);
    Optional<BalanceStatsShift> findByIdAndIsDeletedFalse(Long id);
}
