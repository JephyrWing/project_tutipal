package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** PT-14: 세 공급자 모두 이메일 동의 항목을 요청하지 않는다. */
class OptionalOAuthConfigTest {

    private final ClientRegistrationRepository repo = new OptionalOAuthConfig().clientRegistrations(
            new MockEnvironment()
                    .withProperty("GOOGLE_CLIENT_ID", "g-id").withProperty("GOOGLE_CLIENT_SECRET", "g-secret")
                    .withProperty("NAVER_CLIENT_ID", "n-id").withProperty("NAVER_CLIENT_SECRET", "n-secret")
                    .withProperty("KAKAO_CLIENT_ID", "k-id").withProperty("KAKAO_CLIENT_SECRET", "k-secret"));

    private ClientRegistration client(String id) {
        ClientRegistration registration = repo.findByRegistrationId(id);
        assertThat(registration).as(id).isNotNull();
        return registration;
    }

    @Test
    void kakaoRequestsNicknameOnlyBecauseAccountEmailNeedsBizApp() {
        assertThat(client("kakao").getScopes()).containsExactly("profile_nickname");
    }

    @Test
    void naverRequestsNameOnly() {
        assertThat(client("naver").getScopes()).containsExactly("name");
    }

    @Test
    void googleRequestsProfileOnlyWithoutOpenidOrEmail() {
        assertThat(client("google").getScopes()).containsExactly("profile");
    }

    @Test
    void noProviderAsksForEmail() {
        for (String id : new String[]{"google", "naver", "kakao"}) {
            assertThat(client(id).getScopes()).as(id)
                    .noneMatch(scope -> scope.toLowerCase().contains("email"));
        }
    }
}
