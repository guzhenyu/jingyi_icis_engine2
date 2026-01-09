package com.jingyicare.jingyi_icis_engine.repository.lis;

import java.time.*;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.lis.PatientLisItem;

public interface PatientLisItemRepository extends JpaRepository<PatientLisItem, String> {
    Optional<PatientLisItem> findByReportId(String reportId);

    List<PatientLisItem> findByHisPid(String hisPid);

    List<PatientLisItem> findByHisPidAndAuthTimeBetween(String hisPid, LocalDateTime start, LocalDateTime end);
    List<PatientLisItem> findByHisPidAndAuthTimeIsNull(String hisPid);
}
