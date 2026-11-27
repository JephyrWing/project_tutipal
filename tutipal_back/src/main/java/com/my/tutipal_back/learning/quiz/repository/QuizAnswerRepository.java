package com.my.tutipal_back.learning.quiz.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.my.tutipal_back.learning.quiz.entity.QuizAnswer;

public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, Long> {

    Optional<QuizAnswer> findByQuizIdAndUserId(Long quizId, Long userId);

    List<QuizAnswer> findByQuizIdOrderByIdAsc(Long quizId);

    List<QuizAnswer> findByQuizIdIn(List<Long> quizIds);

    @Query("select a.quizId from QuizAnswer a where a.id = :id")
    Optional<Long> findQuizIdByAnswerId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from QuizAnswer a where a.id = :id")
    Optional<QuizAnswer> findForGrading(@Param("id") Long id);
}
