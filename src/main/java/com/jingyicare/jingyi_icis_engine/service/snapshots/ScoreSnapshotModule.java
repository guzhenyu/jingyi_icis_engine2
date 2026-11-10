package com.jingyicare.jingyi_icis_engine.service.snapshots;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreReader;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class ScoreSnapshotModule implements SnapshotModule {
    public ScoreSnapshotModule(ScoreReader reader) {
        this.reader = reader;
    }

    @Override
    public Kind kind() { return Kind.SCORE_GROUP; }

    @Override
    public String lastModifiedAt(PatientSnapshotPB snapshot) {
        return snapshot.getScoreGroupLmaIso8601();
    }

    @Override
    public String snapshotAt(PatientSnapshotPB snapshot) {
        return snapshot.getScoreGroupSaIso8601();
    }

    @Override
    public void markModified(PatientSnapshotPB.Builder snapshot, String modifiedAt) {
        snapshot.setScoreGroupLmaIso8601(modifiedAt);
    }

    @Override
    public void refresh(Context context, PatientSnapshotPB.Builder snapshot) {
        snapshot.clearScoreGroups().addAllScoreGroups(reader.getRecords(
            context.pid(), context.window().monitoringStartUtc(),
            context.window().monitoringEndUtc()));
        snapshot.setScoreGroupSaIso8601(
            TimeUtils.toIso8601StringWithSeconds(context.snapshotAt(), "UTC"));
    }

    private final ScoreReader reader;
}
