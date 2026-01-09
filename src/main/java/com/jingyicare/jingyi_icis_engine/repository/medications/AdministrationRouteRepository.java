package com.jingyicare.jingyi_icis_engine.repository.medications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.medications.AdministrationRoute;
import com.jingyicare.jingyi_icis_engine.entity.medications.RouteDetails;

public interface AdministrationRouteRepository extends JpaRepository<AdministrationRoute, Integer> {
    List<AdministrationRoute> findByDeptId(String deptId);

    Optional<AdministrationRoute> findByDeptIdAndCode(String deptId, String code);

    List<AdministrationRoute> findByDeptIdAndCodeIn(String deptId, List<String> codes);

    /*
    select r.code, r.is_continuous, it.monitoring_param_code from administration_routes r
    join intake_types it on r.intake_type_id = it.id
    where r.dept_id = :deptId
    */
    @Query("SELECT r.code AS code, r.isContinuous AS isContinuous, it.monitoringParamCode AS monitoringParamCode " +
        "FROM AdministrationRoute r JOIN IntakeType it ON r.intakeTypeId = it.id " +
        "WHERE r.deptId = :deptId"
    )
    List<RouteDetails> findRouteDetailsByDeptId(@Param("deptId") String deptId);

    Optional<AdministrationRoute> findById(Integer id);

    /*
    select r.code, r.is_continuous, it.monitoring_param_code from administration_routes r
    join intake_types it on r.intake_type_id = it.id
    where r.dept_id = :deptId
    */
    @Query("SELECT r.code AS code, r.isContinuous AS isContinuous, it.monitoringParamCode AS monitoringParamCode " +
        "FROM AdministrationRoute r JOIN IntakeType it ON r.intakeTypeId = it.id " +
        "WHERE r.deptId = :deptId AND r.code = :code"
    )
    Optional<RouteDetails> findRouteDetailsByDeptIdAndCode(@Param("deptId") String deptId, @Param("code") String code);
}