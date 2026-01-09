package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.*;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.RawBgaRecord;

public interface RawBgaRecordRepository extends JpaRepository<RawBgaRecord, Long> {
    List<RawBgaRecord> findByMrnBednumAndEffectiveTimeBetween(String mrnBednum, LocalDateTime startTime, LocalDateTime endTime);
}