package com.my.tutipal_back.livekit.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.livekit.entity.HelpRequest;

public interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {

    List<HelpRequest> findBySessionIdAndStatusInOrderByIdAsc(Long sessionId, Collection<HelpRequest.Status> statuses);

    List<HelpRequest> findBySessionIdAndUserIdAndStatusIn(Long sessionId, Long userId, Collection<HelpRequest.Status> statuses);

    long countBySessionIdAndStatus(Long sessionId, HelpRequest.Status status);
}
