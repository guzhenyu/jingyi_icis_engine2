package com.jingyicare.jingyi_icis_engine.repository.tubes;

import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.tubes.TubeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TubeTypeRepository extends JpaRepository<TubeType, Integer> {
    List<TubeType> findAll();

    Optional<TubeType> findById(Integer id);

    Optional<TubeType> findByIdAndIsDeletedFalse(Integer id);

    List<TubeType> findByIdInAndIsDeletedFalse(List<Integer> ids);

    List<TubeType> findByDeptIdAndIsDeletedFalse(String deptId);

    Optional<TubeType> findByDeptIdAndNameAndIsDeletedFalse(String deptId, String name);

    List<TubeType> findByDeptIdAndTypeAndIsDeletedFalse(String deptId, String type);
}