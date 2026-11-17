package com.my.tutipal_back.learning.qna.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.learning.qna.entity.Post;

public interface PostRepository extends JpaRepository<Post, Long> {

    List<Post> findBySessionIdOrderByIdDesc(Long sessionId);
}
