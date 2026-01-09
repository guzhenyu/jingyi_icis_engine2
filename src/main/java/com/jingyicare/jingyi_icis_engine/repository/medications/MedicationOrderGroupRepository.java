package com.jingyicare.jingyi_icis_engine.repository.medications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationOrderGroup;

public interface MedicationOrderGroupRepository extends JpaRepository<MedicationOrderGroup, Long> {
    List<MedicationOrderGroup> findAll();

    Optional<MedicationOrderGroup> findById(Long Id);

    @Query("SELECT mog FROM MedicationOrderGroup mog " +
       "WHERE mog.patientId = :patientId " +
       "AND mog.groupId = :groupId " +
       "ORDER BY mog.id DESC " +
       "LIMIT 1")
    Optional<MedicationOrderGroup> findLatestByPatientIdAndGroupId(
        @Param("patientId") Long patientId, @Param("groupId") String groupId);

    @Query("SELECT mog FROM MedicationOrderGroup mog " +
       "WHERE mog.patientId = :patientId " +
       "AND mog.groupId = :groupId " +
       "ORDER BY mog.id DESC ")
    List<MedicationOrderGroup> findByPatientIdAndGroupId(
        @Param("patientId") Long patientId, @Param("groupId") String groupId);

    @Query("SELECT mog FROM MedicationOrderGroup mog " +
       "WHERE mog.patientId = :patientId " +
       "AND mog.orderValidity = :orderValidity " +
       "ORDER BY mog.id DESC ")
    List<MedicationOrderGroup> findByPatientIdAndOrderValidity(
        @Param("patientId") Long patientId, @Param("orderValidity") Integer orderValidity);

    @Query("SELECT mog FROM MedicationOrderGroup mog WHERE mog.id IN :ids")
    List<MedicationOrderGroup> findByIds(@Param("ids") List<Long> ids);

    // for tests
    List<MedicationOrderGroup> findByPatientId(Long patientId);
}