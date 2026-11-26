package com.my.tutipal_back.learning.focus.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.my.tutipal_back.learning.focus.FocusProperties;

/**
 * PT-90: FocusClient bean. Address and internal key are the same keys as AiClient (app.ai.base-url,
 * app.ai.internal-key) so every client points at the same FastAPI.
 */
@Configuration(proxyBeanMethods = false)
class FocusConfig {

    @Bean
    FocusClient focusClient(@Value("${app.ai.base-url:}") String baseUrl,
                            @Value("${app.ai.internal-key:}") String internalKey,
                            FocusProperties properties) {
        return new FocusClient(baseUrl, internalKey, properties.predictTimeout());
    }
}
