package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.RawBgaRecordDetail;

public interface RawBgaRecordDetailRepository extends JpaRepository<RawBgaRecordDetail, Long> {
    List<RawBgaRecordDetail> findByRecordIdIn(List<Long> recordIds);
}