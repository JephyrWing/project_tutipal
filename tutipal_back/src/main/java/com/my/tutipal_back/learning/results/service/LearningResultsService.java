package com.my.tutipal_back.learning.results.service;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.results.dto.LearningResultsPage;
import com.my.tutipal_back.learning.results.repository.LearningResultsStore;

@Service
public class LearningResultsService {
    private final LearningResultsStore store;
    public LearningResultsService(LearningResultsStore store) { this.store = store; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public LearningResultsPage history(long userId, int page, int size, Long sessionId, String verdict) {
        if (page < 0 || size < 1 || size > 100 || (sessionId != null && sessionId < 1)
                || !Set.of("ALL","PENDING","CORRECT","INCORRECT").contains(verdict)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"조회 조건을 확인해 주세요");
        }
        long count = store.count(userId,sessionId,verdict);
        return new LearningResultsPage(store.list(userId,sessionId,verdict,size,(long)page*size),
                store.sessions(userId),count,page,size,(count+size-1)/size);
    }
}
