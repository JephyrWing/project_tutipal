package com.my.tutipal_back.sessions.repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.sessions.entity.StudySession;
import com.my.tutipal_back.sessions.entity.SessionRole;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StudySession s where s.id = :id")
    Optional<StudySession> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StudySession> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);

    @Query("""
            select s from StudySession s
            where s.ownerId = :userId and s.endedAt is not null
              and exists (select p.id from SessionParticipant p
                          where p.sessionId = s.id and p.userId = :userId and p.role = :role)
            order by s.endedAt desc, s.id desc
            """)
    List<StudySession> findEndedForTeacher(@Param("userId") Long userId, @Param("role") SessionRole role);
}
