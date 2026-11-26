package com.my.tutipal_back.users.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** PT-14: 응답에 이메일이 있어도 읽지 않고, 이메일이 없어도 로그인 정보가 만들어진다. */
class SocialProfileTest {

    @Test
    void kakaoWithoutEmailConsentStillMapsIdAndNickname() {
        var attrs = Map.<String, Object>of(
                "id", 4321L,
                "kakao_account", Map.of("profile", Map.of("nickname", "설민")));

        var profile = SocialProfile.of("kakao", attrs);

        assertThat(profile.providerId()).isEqualTo("4321");
        assertThat(profile.displayName()).isEqualTo("설민");
    }

    @Test
    void emailInResponseIsIgnored() {
        var kakao = SocialProfile.of("kakao", Map.of(
                "id", 1L,
                "kakao_account", Map.of("email", "a@b.c", "profile", Map.of("nickname", "k"))));
        var naver = SocialProfile.of("naver", Map.of(
                "response", Map.of("id", "n1", "email", "a@b.c", "name", "n")));
        var google = SocialProfile.of("google", Map.of("sub", "g1", "email", "a@b.c", "name", "g"));

        for (var profile : new SocialProfile[]{kakao, naver, google}) {
            assertThat(profile.toString()).doesNotContain("a@b.c");
        }
        assertThat(naver.providerId()).isEqualTo("n1");
        assertThat(google.providerId()).isEqualTo("g1");
    }

    @Test
    void missingNameFallsBackToProviderLabel() {
        var profile = SocialProfile.of("kakao", Map.of("id", 7L));

        assertThat(profile.displayName()).isEqualTo("kakao 사용자");
    }

    @Test
    void missingIdIsRejected() {
        assertThatThrownBy(() -> SocialProfile.of("naver", Map.of("response", Map.of("name", "n"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
