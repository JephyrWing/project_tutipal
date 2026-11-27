package com.my.tutipal_back.learning.qna.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.my.tutipal_back.learning.qna.entity.Post;

public interface PostRepository extends JpaRepository<Post, Long> {

    List<Post> findBySessionIdAndHiddenAtIsNullOrderByIdDesc(Long sessionId);

    Optional<Post> findByIdAndHiddenAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Post> findLockedByIdAndHiddenAtIsNull(Long id);
}
