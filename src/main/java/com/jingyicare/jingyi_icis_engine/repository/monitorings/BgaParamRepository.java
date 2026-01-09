package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;

public interface BgaParamRepository extends JpaRepository<BgaParam, Long> {
    List<BgaParam> findAll();
    List<BgaParam> findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(String deptId);
    Optional<BgaParam> findByIdAndIsDeletedFalse(Long id);
    Optional<BgaParam> findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(String deptId, String paramCode);
}