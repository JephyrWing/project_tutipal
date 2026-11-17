package com.my.tutipal_back.ai.client;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.common.exception.ApiException;

/**
 * 로컬 GPU 하나를 챗봇 · 채점 · 문제 만들기가 같이 쓰므로 동시에 LLM 을 부르는 수를 여기 한 곳에서 제한.
 * ponytail: 서버 1대 기준 메모리 세마포어. 서버 여러 대 지원은 별도 설계
 */
@Component
public class AiSlots {

    private final Semaphore slots;
    private final Duration defaultWait;

    public AiSlots(@Value("${app.ai.max-concurrent:2}") int maxConcurrent,
                   @Value("${app.ai.wait-for-slot:30s}") Duration defaultWait) {
        this.slots = new Semaphore(maxConcurrent, true);
        this.defaultWait = defaultWait;
    }

    public <T> T run(Supplier<T> work) {
        return run(defaultWait, work);
    }

    /** 자리가 날 때까지 최대 wait 기다렸다가 실행. 못 얻으면 503. */
    public <T> T run(Duration wait, Supplier<T> work) {
        if (!acquire(wait)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "지금 질문이 많아요. 잠시 후 다시 시도해 주세요");
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    private boolean acquire(Duration wait) {
        try {
            return slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
