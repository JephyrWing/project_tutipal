package com.my.tutipal_back.learning.tutor.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PT-87: registers {@link StudentQuestionSink#NONE} only when no other sink exists (same pattern as
 * ConversationMemoryConfig). The insights ChatQuestionRecorder is a component, so component scanning registers it
 * first and this bean is skipped.
 */
@Configuration(proxyBeanMethods = false)
public class StudentQuestionSinkConfig {

    @Bean
    @ConditionalOnMissingBean(StudentQuestionSink.class)
    StudentQuestionSink noStudentQuestionSink() {
        return StudentQuestionSink.NONE;
    }
}
