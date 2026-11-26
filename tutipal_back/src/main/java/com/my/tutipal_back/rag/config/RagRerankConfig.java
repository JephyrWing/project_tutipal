package com.my.tutipal_back.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.my.tutipal_back.ai.client.AiAccessHeaders;
import com.my.tutipal_back.ai.client.RerankClient;

/**
 * PT-86: 재순위화 클라이언트. 주소와 내부 키는 AiClient 와 같은 키(app.ai.base-url · app.ai.internal-key)를 읽어
 * 두 클라이언트가 서로 다른 FastAPI 를 가리키는 일이 없게 한다.
 */
@Configuration(proxyBeanMethods = false)
class RagRerankConfig {

    @Bean
    RerankClient rerankClient(@Value("${app.ai.base-url:}") String baseUrl,
                              @Value("${app.ai.internal-key:}") String internalKey,
                              AiAccessHeaders access,
                              RagRerankProperties properties) {
        return new RerankClient(baseUrl, internalKey, access, properties.timeout());
    }
}
