package com.my.tutipal_back.learning.quiz.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.my.tutipal_back.learning.quiz.entity.Quiz;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    List<Quiz> findBySessionIdOrderByIdAsc(Long sessionId);

    List<Quiz> findBySessionIdAndClosesAtAfterOrderByIdAsc(Long sessionId, Instant now);

    boolean existsBySessionIdAndClosesAtAfter(Long sessionId, Instant cutoff);

    @Query("select q.sessionId from Quiz q where q.id = :quizId")
    Optional<Long> findSessionIdByQuizId(Long quizId);
}
