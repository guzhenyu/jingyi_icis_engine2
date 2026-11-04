package com.jingyicare.jingyi_icis_engine.repository.snapshots;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.snapshots.PatientSnapshot;

public interface PatientSnapshotRepository extends JpaRepository<PatientSnapshot, Long> {
    List<PatientSnapshot> findByPidAndStatisticsDateIn(Long pid, Collection<LocalDate> dates);
}
