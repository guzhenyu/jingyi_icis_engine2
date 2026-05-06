package com.jingyicare.jingyi_icis_engine.repository.archives;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.archives.PatientArchive;

@Repository
public interface PatientArchiveRepository extends JpaRepository<PatientArchive, Integer> {
    Optional<PatientArchive> findByPidAndTypeAndLocalMidnightUtcAndIsDeletedFalse(
        Long pid, Integer type, LocalDateTime localMidnightUtc);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select archive
        from PatientArchive archive
        where archive.pid = :pid
            and archive.type = :type
            and archive.localMidnightUtc = :localMidnightUtc
            and archive.isDeleted = false
        """)
    Optional<PatientArchive> findActiveForUpdate(
        @Param("pid") Long pid,
        @Param("type") Integer type,
        @Param("localMidnightUtc") LocalDateTime localMidnightUtc);
}
