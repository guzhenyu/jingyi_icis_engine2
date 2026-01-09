package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaCategoryMapping;

public interface BgaCategoryMappingRepository extends JpaRepository<BgaCategoryMapping, Long> {
    List<BgaCategoryMapping> findAll();
    List<BgaCategoryMapping> findByDeptIdAndIsDeletedFalse(String deptId);
    Optional<BgaCategoryMapping> findByDeptIdAndBgaCategoryIdAndLisCategoryCodeAndIsDeletedFalse(
            String deptId, Integer bgaCategoryId, String lisCategoryCode);
}