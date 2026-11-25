package com.jingyicare.jingyi_icis_engine.service.settings.stats;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.settings.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.repository.settings.BalanceStatsShiftJdbc;

@Service
public class StatsDayConfigReader {
    public StatsDayConfigReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按生效时间倒序返回科室未删除的统计日配置。 */
    public List<BalanceStatsShift> listActiveByDept(String deptId) {
        return BalanceStatsShiftJdbc.findActiveByDept(jdbcTemplate, deptId);
    }

    private final JdbcTemplate jdbcTemplate;
}
