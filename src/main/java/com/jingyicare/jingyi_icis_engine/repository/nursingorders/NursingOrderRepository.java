package com.jingyicare.jingyi_icis_engine.repository.nursingorders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingOrderRepository extends JpaRepository<NursingOrder, Long> {
    /*
     * 根据start_time和end_time查询执行记录
     */
    // start_time is null && end_time is null, 则根据plan_time查询
    @Query("SELECT no FROM NursingOrder no" +
        " WHERE no.pid = :patientId AND no.isDeleted = false" +
        " AND no.orderTime >= :startUtcTime AND no.orderTime < :endUtcTime")
    List<NursingOrder> findByPatientIdAndTime(
        @Param("patientId") Long patientId,
        @Param("startUtcTime") LocalDateTime startUtcTime,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    List<NursingOrder> findByPidAndIsDeletedFalse(Long patientId);

    Optional<NursingOrder> findByIdAndIsDeletedFalse(Long id);

    Optional<NursingOrder> findByPidAndOrderTemplateIdAndOrderTime(Long patientId, Integer orderTemplateId, LocalDateTime orderTime);
}