package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.repository.scores.PatientScoreRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

/**
 * 普通评分查询与日快照刷新共用的只读转换，不重新生成已存评分文本。
 * 不添加 @Transactional、不调用 LockExecutor：事务由调用方编排，快照复用已有锁事务。
 */
@Service
public class ScoreReader {
    public ScoreReader(ConfigProtoService protoService, PatientScoreRepository repository) {
        this.zoneId = protoService.getZoneId();
        this.repository = repository;
    }

    /** 日快照使用全部评分类型的 UTC 半开窗口，按时间、ID 升序。 */
    public List<ScoreGroupPB> getRecords(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        return repository.findPatientScoresInWindow(pid, startUtc, endUtc).stream()
            .map(this::toProto).toList();
    }

    /** 普通列表查询保留原有结束时间包含、按时间降序的语义。 */
    public List<ScoreGroupPB> getRecords(
        Long pid, String code, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        return repository.findPatientScores(pid, code, startUtc, endUtc).stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime).reversed())
            .map(this::toProto).toList();
    }

    private ScoreGroupPB toProto(PatientScore record) {
        ScoreGroupPB result = ScoreMapper.toScoreGroupPB(record, zoneId);
        if (result == null) {
            throw new IllegalStateException("Invalid stored score: " + record.getId());
        }
        return result;
    }

    private final String zoneId;
    private final PatientScoreRepository repository;
}
