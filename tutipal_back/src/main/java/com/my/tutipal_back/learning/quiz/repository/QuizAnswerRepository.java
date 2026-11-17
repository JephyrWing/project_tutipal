package com.my.tutipal_back.learning.quiz.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.learning.quiz.entity.QuizAnswer;

public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, Long> {

    Optional<QuizAnswer> findByQuizIdAndUserId(Long quizId, Long userId);

    List<QuizAnswer> findByQuizIdOrderByIdAsc(Long quizId);

    List<QuizAnswer> findByQuizIdIn(List<Long> quizIds);
}
