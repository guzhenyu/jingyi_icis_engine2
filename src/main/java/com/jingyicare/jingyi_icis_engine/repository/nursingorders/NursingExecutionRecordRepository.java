package com.jingyicare.jingyi_icis_engine.repository.nursingorders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingExecutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingExecutionRecordRepository extends JpaRepository<NursingExecutionRecord, Long> {
    interface NursingExecutionRecordReportRow {
        NursingExecutionRecord getExecutionRecord();

        String getOrderName();
    }

    List<NursingExecutionRecord> findByNursingOrderIdInAndPlanTimeBetween(
        List<Long> nursingOrderIds, LocalDateTime startTime, LocalDateTime endTime);
    
    List<NursingExecutionRecord> findByNursingOrderIdAndPlanTimeBetweenAndIsDeletedFalse(
        Long nursingOrderId, LocalDateTime startTime, LocalDateTime endTime);

    @Query(value = "SELECT * FROM nursing_execution_records  WHERE nursing_order_id = :orderId AND is_deleted = false ORDER BY plan_time DESC LIMIT 1", nativeQuery = true)
    Optional<NursingExecutionRecord> findLatestValidRecord(@Param("orderId") Long orderId);

    @Query("""
        select record as executionRecord,
               nursingOrder.name as orderName
        from NursingExecutionRecord record
        join NursingOrder nursingOrder on nursingOrder.id = record.nursingOrderId
        where record.pid = :pid
          and record.completedTime is not null
          and record.completedTime >= :startTime
          and record.completedTime < :endTime
          and record.isDeleted = false
          and nursingOrder.isDeleted = false
        order by record.completedTime asc, record.id asc
        """)
    List<NursingExecutionRecordReportRow> findReportNursingExecutionRecords(
        @Param("pid") Long pid,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );
}
