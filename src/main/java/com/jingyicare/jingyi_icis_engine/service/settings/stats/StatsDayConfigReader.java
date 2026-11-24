package com.jingyicare.jingyi_icis_engine.service.settings.stats;

import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;

@Service
public class StatsDayConfigReader {
    public StatsDayConfigReader(BalanceStatsShiftRepository repository) {
        this.repository = repository;
    }

    /** 按生效时间倒序返回科室未删除的统计日配置。 */
    public List<BalanceStatsShift> listActiveByDept(String deptId) {
        return repository.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId);
    }

    private final BalanceStatsShiftRepository repository;
}
