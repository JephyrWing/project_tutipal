package com.my.tutipal_back.learning.tutor.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PT-85: ConversationMemory 구현이 따로 없을 때만 기본 구현을 등록한다. PT-106 이 저장 구현을
 * {@code @Component} 로 추가하면 컴포넌트 스캔이 먼저 등록하므로 이 빈은 만들어지지 않는다 (빈 2개로 기동 실패 방지).
 */
@Configuration(proxyBeanMethods = false)
public class ConversationMemoryConfig {

    @Bean
    @ConditionalOnMissingBean(ConversationMemory.class)
    ConversationMemory noStoredConversationMemory() {
        return new NoStoredConversationMemory();
    }
}
