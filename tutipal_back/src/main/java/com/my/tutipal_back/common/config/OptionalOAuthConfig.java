package com.my.tutipal_back.common.config;

import java.util.ArrayList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
public class OptionalOAuthConfig {
    @Bean
    @Conditional(Configured.class)
    ClientRegistrationRepository clientRegistrations(Environment env) {
        var clients = new ArrayList<ClientRegistration>();
        for (String provider : new String[]{"google", "naver", "kakao"}) {
            String id = env.getProperty(provider.toUpperCase() + "_CLIENT_ID", "");
            String secret = env.getProperty(provider.toUpperCase() + "_CLIENT_SECRET", "");
            if (id.isBlank() || secret.isBlank()) continue;
            ClientRegistration.Builder b;
            // openid를 빼야 OIDC 대신 일반 OAuth2 흐름을 타서 SocialOAuth2UserService가 우리 users.id를 principal로 만든다.
            // (openid가 있으면 principal 이름이 구글 sub(21자리)가 되어 AppLoginSuccessHandler에서 Long 변환이 실패한다)
            if (provider.equals("google")) b = CommonOAuth2Provider.GOOGLE.getBuilder(provider).scope("profile", "email");
            else if (provider.equals("naver")) b = ClientRegistration.withRegistrationId(provider)
                    .authorizationUri("https://nid.naver.com/oauth2.0/authorize").tokenUri("https://nid.naver.com/oauth2.0/token")
                    .userInfoUri("https://openapi.naver.com/v1/nid/me").userNameAttributeName("response").scope("name", "email");
            else b = ClientRegistration.withRegistrationId(provider)
                    .authorizationUri("https://kauth.kakao.com/oauth/authorize").tokenUri("https://kauth.kakao.com/oauth/token")
                    .userInfoUri("https://kapi.kakao.com/v2/user/me").userNameAttributeName("id")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST).scope("profile_nickname", "account_email");
            clients.add(b.clientId(id).clientSecret(secret).clientName(provider)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").build());
        }
        if (clients.isEmpty()) throw new IllegalStateException("OAuth client secret도 설정해 주세요");
        return new InMemoryClientRegistrationRepository(clients);
    }
    static class Configured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            for(String provider:new String[]{"GOOGLE","NAVER","KAKAO"})
                if(!context.getEnvironment().getProperty(provider+"_CLIENT_ID","").isBlank()
                    && !context.getEnvironment().getProperty(provider+"_CLIENT_SECRET","").isBlank())return true;
            return false;
        }
    }
}
