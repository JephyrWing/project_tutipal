package com.my.tutipal_back.learning.insights.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.learning.insights.dto.MasteryResponse;
import com.my.tutipal_back.learning.insights.repository.MasteryStore;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-9..15: BKT mastery per current student x session concept, recomputed from the stored answers on every
 * request (nothing saved, so a regrade is reflected on the next request). Teacher of the session only; ended
 * sessions can still be viewed.
 */
@Service
public class MasteryService {

    /** Fewer graded observations than this make the cell low confidence. */
    static final int CONFIDENT_OBSERVATIONS = 3;

    private final StudySessionService sessions;
    private final MasteryStore store;

    public MasteryService(StudySessionService sessions, MasteryStore store) {
        this.sessions = sessions;
        this.store = store;
    }

    private record CellKey(long userId, long conceptId) {
    }

    private static final class Estimate {
        private double p = Bkt.P_INIT;
        private int observations;
    }

    @Transactional(readOnly = true)
    public MasteryResponse mastery(Long userId, Long sessionId) {
        sessions.requireTeacherOf(userId, sessionId);
        MasteryStore.Snapshot snapshot = store.load(sessionId);

        Set<Long> currentStudents = new HashSet<>();
        snapshot.students().forEach(s -> currentStudents.add(s.userId()));
        Map<CellKey, Estimate> estimates = new HashMap<>();
        for (MasteryStore.Observation o : snapshot.observations()) {
            if (o.correct() == null || !currentStudents.contains(o.userId())) {
                continue;   // ungraded, or a student who is no longer in the session
            }
            Estimate e = estimates.computeIfAbsent(new CellKey(o.userId(), o.conceptId()), k -> new Estimate());
            e.p = Bkt.update(e.p, o.correct(), Bkt.guess(o.type(), o.choiceCount()));
            e.observations++;
        }

        List<MasteryResponse.StudentRow> rows = new ArrayList<>();
        Map<Long, List<Integer>> knownByConcept = new HashMap<>();
        for (MasteryStore.StudentInfo student : snapshot.students()) {
            List<MasteryResponse.Cell> cells = new ArrayList<>();
            for (MasteryStore.ConceptInfo concept : snapshot.concepts()) {
                Estimate e = estimates.get(new CellKey(student.userId(), concept.id()));
                int observations = e == null ? 0 : e.observations;
                Integer pKnown = e == null ? null : (int) Math.round(e.p * 100);
                if (pKnown != null) {
                    knownByConcept.computeIfAbsent(concept.id(), k -> new ArrayList<>()).add(pKnown);
                }
                cells.add(new MasteryResponse.Cell(concept.id(), pKnown, observations,
                        observations < CONFIDENT_OBSERVATIONS));
            }
            rows.add(new MasteryResponse.StudentRow(student.userId(), student.name(), List.copyOf(cells)));
        }

        List<MasteryResponse.ConceptColumn> columns = new ArrayList<>();
        for (MasteryStore.ConceptInfo concept : snapshot.concepts()) {
            List<Integer> known = knownByConcept.get(concept.id());
            Integer classAvg = known == null ? null
                    : (int) Math.round(known.stream().mapToInt(Integer::intValue).average().orElseThrow());
            columns.add(new MasteryResponse.ConceptColumn(concept.id(), concept.name(), concept.quizCount(), classAvg,
                    classAvg != null && classAvg < 50));
        }
        return new MasteryResponse(List.copyOf(columns), List.copyOf(rows));
    }
}
