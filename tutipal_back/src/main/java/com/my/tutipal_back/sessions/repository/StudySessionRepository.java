package com.my.tutipal_back.sessions.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.sessions.entity.StudySession;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    Optional<StudySession> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
