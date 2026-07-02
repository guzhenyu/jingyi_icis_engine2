package com.jingyicare.jingyi_icis_engine.repository.therapies;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.therapies.SepsisAndSepticShockBundle;

public interface SepsisAndSepticShockBundleRepository extends JpaRepository<SepsisAndSepticShockBundle, Long> {
    Optional<SepsisAndSepticShockBundle> findByPid(Long pid);
    List<SepsisAndSepticShockBundle> findByPidIn(Collection<Long> pids);
}
