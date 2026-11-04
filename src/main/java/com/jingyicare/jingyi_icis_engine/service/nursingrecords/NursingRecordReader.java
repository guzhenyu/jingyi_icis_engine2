package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordPB;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
public class NursingRecordReader {
    public NursingRecordReader(
        ConfigProtoService protoService, NursingRecordRepository recordRepo
    ) {
        this.zoneId = protoService.getConfig().getZoneId();
        this.recordRepo = recordRepo;
    }

    /**
     * 查询 UTC 半开区间 [queryStartUtc, queryEndUtc) 内的有效护理记录，供普通护理查询和快照刷新复用。
     * 不添加 @Transactional，也不调用 LockExecutor：本方法只负责查询和 PB 转换，不编排事务。
     * 快照场景复用调用方 LockExecutor 已建立的事务；普通查询沿用 Repository 的查询方式。
     */
    public List<NursingRecordPB> getRecords(
        Long pid, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        return recordRepo.findReportNursingRecords(pid, queryStartUtc, queryEndUtc)
            .stream().map(this::toProto).toList();
    }

    private NursingRecordPB toProto(NursingRecord record) {
        return NursingRecordPB.newBuilder()
            .setId(record.getId())
            .setPatientId(record.getPatientId())
            .setContent(record.getContent())
            .setEffectiveTimeIso8601(
                TimeUtils.toIso8601String(record.getEffectiveTime(), zoneId))
            .setSource(Objects.toString(record.getSource(), ""))
            .setPatientCriticalLisHandlingId(record.getPatientCriticalLisHandlingId() == null
                ? 0 : record.getPatientCriticalLisHandlingId())
            .setReviewedBy(Objects.toString(record.getReviewedBy(), ""))
            .setReviewedByAccountName(Objects.toString(record.getReviewedByAccountName(), ""))
            .setReviewedAtIso8601(TimeUtils.toIso8601String(record.getReviewedAt(), zoneId))
            .setModifiedBy(Objects.toString(record.getModifiedBy(), ""))
            .setModifiedByAccountName(Objects.toString(record.getModifiedByAccountName(), ""))
            .setModifiedAtIso8601(TimeUtils.toIso8601String(record.getModifiedAt(), zoneId))
            .setCreatedBy(Objects.toString(record.getCreatedBy(), ""))
            .setCreatedByAccountName(Objects.toString(record.getCreatedByAccountName(), ""))
            .setCreatedAtIso8601(TimeUtils.toIso8601String(record.getCreatedAt(), zoneId))
            .build();
    }

    private final String zoneId;
    private final NursingRecordRepository recordRepo;
}
