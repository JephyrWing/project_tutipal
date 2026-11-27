package com.my.tutipal_back.learning.quiz.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.my.tutipal_back.learning.quiz.entity.QuizDraftApproval;

public interface QuizDraftApprovalRepository extends JpaRepository<QuizDraftApproval, Long> {
    Optional<QuizDraftApproval> findByIdAndSessionIdAndTeacherId(Long id, Long sessionId, Long teacherId);

    @Modifying
    @Query("delete from QuizDraftApproval d where d.sessionId = :sessionId and d.sentQuizId is null")
    void deleteUnsent(@Param("sessionId") long sessionId);
}
