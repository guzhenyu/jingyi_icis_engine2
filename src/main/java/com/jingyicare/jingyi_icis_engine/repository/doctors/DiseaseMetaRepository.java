package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.doctors.DiseaseMeta;

public interface DiseaseMetaRepository extends JpaRepository<DiseaseMeta, String> {
    List<DiseaseMeta> findAll();

    Optional<DiseaseMeta> findByCode(String code);
}