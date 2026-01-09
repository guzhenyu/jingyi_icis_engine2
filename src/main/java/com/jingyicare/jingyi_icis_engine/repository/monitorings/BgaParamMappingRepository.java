package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParamMapping;

public interface BgaParamMappingRepository extends JpaRepository<BgaParamMapping, Long> {
    List<BgaParamMapping> findAll();
    List<BgaParamMapping> findByDeptIdAndIsDeletedFalse(String deptId);
    Optional<BgaParamMapping> findByDeptIdAndBgaCodeAndLisResultCodeAndIsDeletedFalse(
            String deptId, String bgaCode, String lisResultCode);
}