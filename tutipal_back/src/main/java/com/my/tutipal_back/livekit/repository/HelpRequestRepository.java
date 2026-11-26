package com.my.tutipal_back.livekit.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.livekit.entity.HelpRequest;

public interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {

    @Query("select h.sessionId from HelpRequest h where h.id = :id")
    Optional<Long> sessionIdOf(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HelpRequest h where h.id = :id")
    Optional<HelpRequest> lockById(@Param("id") Long id);

    List<HelpRequest> findBySessionIdAndStatusInOrderByIdAsc(Long sessionId, Collection<HelpRequest.Status> statuses);

    List<HelpRequest> findBySessionIdAndUserIdAndStatusIn(Long sessionId, Long userId, Collection<HelpRequest.Status> statuses);

    List<HelpRequest> findByUserIdAndStatusInOrderByIdAsc(Long userId, Collection<HelpRequest.Status> statuses);

    long countBySessionIdAndStatus(Long sessionId, HelpRequest.Status status);
}
