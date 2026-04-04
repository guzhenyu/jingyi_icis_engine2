package com.jingyicare.jingyi_icis_engine.repository.settings;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;

public interface SystemSettingsRepository extends JpaRepository<SystemSettings, Integer> {
    List<SystemSettings> findAll();
    Optional<SystemSettings> findById(Integer functionId);
}
