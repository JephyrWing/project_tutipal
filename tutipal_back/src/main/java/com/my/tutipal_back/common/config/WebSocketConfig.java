package com.my.tutipal_back.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.my.tutipal_back.common.realtime.StompAuthInterceptor;

// 로그인 서블릿 세션으로 핸드셰이크가 인증됨: /ws 는 SecurityConfig 의 authenticated 규칙을 따름.
// 허용한 Electron 브리지/서버 Origin만 받는다.
// 구독 권한 · SEND 차단은 StompAuthInterceptor.
// ponytail: simple broker = 서버 1대 기준. 서버 여러 대 지원은 별도 설계
// SessionWebSocketGuard가 세션 만료·로그아웃 시 열린 구독도 최대5초 안에 닫는다.
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;
    private final com.my.tutipal_back.common.realtime.SessionWebSocketGuard sessionGuard;
    @org.springframework.beans.factory.annotation.Value("${app.allowed-origins:http://127.0.0.1:*,http://localhost:*}")
    private String[] allowedOrigins;

    public WebSocketConfig(StompAuthInterceptor authInterceptor, com.my.tutipal_back.common.realtime.SessionWebSocketGuard sessionGuard) {
        this.authInterceptor = authInterceptor;
        this.sessionGuard = sessionGuard;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins)
            .addInterceptors(sessionGuard);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
    @Override public void configureWebSocketTransport(org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionGuard);
    }
}
