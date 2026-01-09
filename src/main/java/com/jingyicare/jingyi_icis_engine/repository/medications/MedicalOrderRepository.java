package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicalOrder;

public interface MedicalOrderRepository extends JpaRepository<MedicalOrder, Long> {
    List<MedicalOrder> findAll();

    Optional<MedicalOrder> findByOrderId(String orderId);

    @Query("SELECT mo FROM MedicalOrder mo WHERE mo.hisPatientId = :hisPatientId AND mo.orderTime >= :admissionTime ORDER BY mo.orderTime")
    List<MedicalOrder> findByHisPatientIdAndAdmissionTime(@Param("hisPatientId") String hisPatientId, @Param("admissionTime") LocalDateTime admissionTime);
}