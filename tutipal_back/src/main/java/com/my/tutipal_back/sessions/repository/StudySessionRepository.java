package com.my.tutipal_back.sessions.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.sessions.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StudySession s where s.id = :id")
    Optional<StudySession> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StudySession> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
