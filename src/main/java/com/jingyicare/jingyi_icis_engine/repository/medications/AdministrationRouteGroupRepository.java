package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.jingyicare.jingyi_icis_engine.entity.medications.AdministrationRouteGroup;

public interface AdministrationRouteGroupRepository  extends JpaRepository<AdministrationRouteGroup, Integer> {
    List<AdministrationRouteGroup> findAll();

    List<AdministrationRouteGroup> findByIdIn(List<Integer> ids);

    Optional<AdministrationRouteGroup> findById(Integer id);
}