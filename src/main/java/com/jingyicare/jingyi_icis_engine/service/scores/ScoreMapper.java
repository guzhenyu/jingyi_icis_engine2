package com.jingyicare.jingyi_icis_engine.service.scores;

import java.util.Objects;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public final class ScoreMapper {
    private ScoreMapper() {}

    public static PatientScore toPatientScoreEntity(
        ScoreGroupPB scoreGroup, String modifiedBy, String modifiedByAccountName, LocalDateTime modifiedAt
    ) {
        LocalDateTime effectiveTime =
            TimeUtils.fromIso8601String(scoreGroup.getRecordedAtIso8601(), "UTC");
        if (effectiveTime != null) {
            effectiveTime = effectiveTime.truncatedTo(ChronoUnit.MINUTES);
        }

        return PatientScore.builder()
            .pid(scoreGroup.getPid())
            .scoreGroupCode(scoreGroup.getCode())
            .score(ProtoUtils.encodeScoreGroupPB(scoreGroup))
            .scoreStr(scoreGroup.getGroupScoreText())
            .effectiveTime(effectiveTime)
            .note(scoreGroup.getNote())
            .isDeleted(false)
            .modifiedBy(modifiedBy)
            .modifiedByAccountName(modifiedByAccountName)
            .modifiedAt(modifiedAt)
            .build();
    }

    public static ScoreGroupPB toScoreGroupPB(PatientScore record, String zoneId) {
        ScoreGroupPB storedPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
        if (storedPb == null) return null;

        String scoreText = record.getScoreStr();
        if (StrUtils.isBlank(scoreText)) scoreText = storedPb.getGroupScoreText();
        if (StrUtils.isBlank(scoreText)) scoreText = "";

        return storedPb.toBuilder()
            .setGroupScoreText(scoreText)
            .setId(record.getId() == null ? 0 : record.getId())
            .setPid(record.getPid() == null ? 0 : record.getPid())
            .setRecordedBy(Objects.toString(record.getModifiedBy(), ""))
            .setRecordedByAccountName(Objects.toString(record.getRawModifiedByAccountName(), ""))
            .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), zoneId))
            .setNote(Objects.toString(record.getNote(), ""))
            .build();
    }
}
