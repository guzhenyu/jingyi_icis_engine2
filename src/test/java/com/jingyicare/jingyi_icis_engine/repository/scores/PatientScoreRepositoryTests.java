package com.jingyicare.jingyi_icis_engine.repository.scores;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Transactional
public class PatientScoreRepositoryTests extends TestsBase {
    private static final Long PID = 9900401L;
    private static final Long OTHER_PID = 9900402L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 22, 0, 0);
    private static final LocalDateTime END = START.plusDays(1);

    @Autowired
    private PatientScoreRepository repository;
    @Autowired
    private EntityManager entityManager;

    private PatientScore atStart;
    private PatientScore firstAtSameTime;
    private PatientScore secondAtSameTime;
    private PatientScore historicalCode;
    private PatientScore atEnd;

    @BeforeEach
    void seedWindowRecords() {
        // Insert out of time order; at the shared time, code 2 precedes code 1 by ID.
        atEnd = save(PID, "test_score_1", END, false);
        historicalCode = save(PID, "retired_ns04_score", START.plusHours(12), false);
        firstAtSameTime = save(PID, "test_score_2", START.plusHours(6), false);
        secondAtSameTime = save(PID, "test_score_1", START.plusHours(6), false);
        atStart = save(PID, "test_score_1", START, false);
        save(PID, "test_score_1", START.minusSeconds(1), false);
        save(PID, "test_score_1", START.plusHours(8), true);
        save(OTHER_PID, "test_score_1", START.plusHours(9), false);
        flushAndClear();
    }

    @Test
    void shouldReadAllActiveCodesInHalfOpenWindowOrderedByTimeThenId() {
        assertThat(firstAtSameTime.getId()).isLessThan(secondAtSameTime.getId());
        assertThat(repository.findPatientScoresInWindow(PID, START, END))
            .extracting(PatientScore::getId)
            .containsExactly(atStart.getId(), firstAtSameTime.getId(),
                secondAtSameTime.getId(), historicalCode.getId());
    }

    @Test
    void shouldAssignSharedBoundaryOnlyToNextWindowAndKeepFutureRecords() {
        assertThat(repository.findPatientScoresInWindow(PID, START, END))
            .extracting(PatientScore::getId).doesNotContain(atEnd.getId());
        assertThat(repository.findPatientScoresInWindow(PID, END, END.plusDays(1)))
            .extracting(PatientScore::getId).containsExactly(atEnd.getId());
        assertThat(repository.findPatientScoresInWindow(PID, END.plusDays(1), END.plusDays(2)))
            .isEmpty();

        LocalDateTime futureStart = LocalDateTime.of(2099, 1, 1, 0, 0);
        PatientScore future = save(PID, "test_score_1", futureStart.plusHours(1), false);
        flushAndClear();
        assertThat(repository.findPatientScoresInWindow(PID, futureStart, futureStart.plusDays(1)))
            .extracting(PatientScore::getId).containsExactly(future.getId());
    }

    @Test
    void shouldKeepEndInclusiveForExistingAllCodeBetweenQuery() {
        assertThat(repository.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(PID, START, END))
            .extracting(PatientScore::getId)
            .containsExactlyInAnyOrder(atStart.getId(), firstAtSameTime.getId(),
                secondAtSameTime.getId(), historicalCode.getId(), atEnd.getId());
    }

    private PatientScore save(Long pid, String code, LocalDateTime time, boolean deleted) {
        return repository.save(PatientScore.builder()
            .pid(pid)
            .scoreGroupCode(code)
            .score(ProtoUtils.encodeScoreGroupPB(ScoreGroupPB.newBuilder().setCode(code).build()))
            .scoreStr("已存评分文本")
            .effectiveTime(time)
            .isDeleted(deleted)
            .modifiedAt(time)
            .build());
    }

    private void flushAndClear() {
        repository.flush();
        entityManager.clear();
    }
}
