package com.jingyicare.jingyi_icis_engine.service.snapshots;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.NursingRecordReader;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class NursingRecordSnapshotModule implements SnapshotModule {
    public NursingRecordSnapshotModule(NursingRecordReader reader) {
        this.reader = reader;
    }

    @Override
    public Kind kind() {
        return Kind.NURSING_RECORD;
    }

    @Override
    public String lastModifiedAt(PatientSnapshotPB snapshot) {
        return snapshot.getNursingRecordLmaIso8601();
    }

    @Override
    public String snapshotAt(PatientSnapshotPB snapshot) {
        return snapshot.getNursingRecordSaIso8601();
    }

    @Override
    public void markModified(PatientSnapshotPB.Builder snapshot, String modifiedAt) {
        snapshot.setNursingRecordLmaIso8601(modifiedAt);
    }

    @Override
    public void refresh(Context context, PatientSnapshotPB.Builder snapshot) {
        snapshot.clearNursingRecords().addAllNursingRecords(reader.getRecords(
            context.pid(), context.window().monitoringStartUtc(),
            context.window().monitoringEndUtc()));
        snapshot.setNursingRecordSaIso8601(
            TimeUtils.toIso8601StringWithSeconds(context.snapshotAt(), "UTC"));
    }

    private final NursingRecordReader reader;
}
