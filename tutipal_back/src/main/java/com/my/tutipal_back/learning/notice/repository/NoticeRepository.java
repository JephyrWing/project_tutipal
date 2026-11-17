package com.my.tutipal_back.learning.notice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.learning.notice.entity.Notice;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    List<Notice> findBySessionIdOrderByIdDesc(Long sessionId);
}
