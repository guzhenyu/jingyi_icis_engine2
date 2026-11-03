package com.jingyicare.jingyi_icis_engine.service.scores;

import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public final class PatientScoreMapper {
    private PatientScoreMapper() {}

    public static ScoreGroupPB toProto(PatientScore record, String zoneId) {
        ScoreGroupPB storedPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
        if (storedPb == null) return null;

        String scoreText = record.getScoreStr();
        if (StrUtils.isBlank(scoreText)) scoreText = storedPb.getGroupScoreText();
        if (StrUtils.isBlank(scoreText)) scoreText = "";

        return storedPb.toBuilder()
            .setGroupScoreText(scoreText)
            .setId(record.getId() == null ? 0 : record.getId())
            .setRecordedBy(Objects.toString(record.getModifiedBy(), ""))
            .setRecordedByAccountName(Objects.toString(record.getRawModifiedByAccountName(), ""))
            .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), zoneId))
            .setNote(Objects.toString(record.getNote(), ""))
            .build();
    }
}
