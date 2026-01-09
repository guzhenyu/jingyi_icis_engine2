package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientDevice;

public interface PatientDeviceRepository extends JpaRepository<PatientDevice, Long> {
    List<PatientDevice> findAll();

    List<PatientDevice> findAllByIsDeletedFalse();

    List<PatientDevice> findAllByIsDeletedFalseAndUnbindingTimeIsNull();

    List<PatientDevice> findByPatientIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
        Long patientId,
        LocalDateTime start,
        LocalDateTime end
    );

    List<PatientDevice> findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(
        Long patientId,
        LocalDateTime end
    );

    List<PatientDevice> findByDeviceIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
        Integer deviceId,
        LocalDateTime start,
        LocalDateTime end
    );

    List<PatientDevice> findByDeviceIdAndUnbindingTimeIsNullAndBindingTimeBefore(
        Integer deviceId,
        LocalDateTime end
    );

    List<PatientDevice> findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(Integer deviceId);

    List<PatientDevice> findByPatientIdAndUnbindingTimeIsNotNullAndIsDeletedFalse(Long patientId);

    List<PatientDevice> findByPatientIdAndUnbindingTimeNullAndIsDeletedFalse(Long patientId);

    Optional<PatientDevice> findByPatientIdAndDeviceIdAndBindingTimeAndIsDeletedFalse(
            Long patientId,
            Integer deviceId,
            LocalDateTime bindingTime
    );

    Optional<PatientDevice> findByPatientIdAndDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(
            Long patientId,
            Integer deviceId
    );
}
