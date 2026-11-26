package com.my.tutipal_back.users.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.users.dto.SocialProfile;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.AccountLocks;
import com.my.tutipal_back.users.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceNameTest {
    private UserRepository repository;
    private AccountLocks locks;
    private UserService service;
    private User user;

    @BeforeEach
    void setup() {
        repository = mock(UserRepository.class);
        locks = mock(AccountLocks.class);
        service = new UserService(repository, locks);
        user = new User("google", "social-1", "가입 이름");
        ReflectionTestUtils.setField(user, "id", 12L);
    }

    @Test
    void editedNameSurvivesRepeatedSocialLogins() {
        when(repository.findById(12L)).thenReturn(Optional.of(user));
        when(repository.findByProviderAndProviderId("google", "social-1")).thenReturn(Optional.of(user));
        assertThat(service.updateName(12L, "  새 이름  ").name()).isEqualTo("새 이름");
        for (int i = 0; i < 2; i++) {
            assertThat(service.upsert(new SocialProfile("google", "social-1", "소셜 최신 이름")).getName())
                    .isEqualTo("새 이름");
        }
        assertThat(service.getMe(12L).provider()).isEqualTo("google");
        verify(repository, never()).save(any());
    }

    @Test
    void firstSignupUsesSocialName() {
        when(repository.findByProviderAndProviderId("naver", "new-id")).thenReturn(Optional.empty());
        when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(service.upsert(new SocialProfile("naver", "new-id", "첫 이름")).getName()).isEqualTo("첫 이름");
    }

    @Test
    void existingNameAlsoStaysUntilUserEditsIt() {
        when(repository.findByProviderAndProviderId("google", "social-1")).thenReturn(Optional.of(user));
        assertThat(service.upsert(new SocialProfile("google", "social-1", "외부 변경 이름")).getName())
                .isEqualTo("가입 이름");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\u00a0\u3000", "a\nb", "a\tb", "a\u0000b"})
    void invalidNameCannotChangeStoredName(String name) {
        assertThatThrownBy(() -> service.updateName(12L, name)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(user.getName()).isEqualTo("가입 이름");
        verifyNoInteractions(repository);
    }

    @Test
    void maximumLengthIsAcceptedButLongerNameIsRejected() {
        when(repository.findById(12L)).thenReturn(Optional.of(user));
        assertThat(service.updateName(12L, "가".repeat(50)).name()).hasSize(50);
        assertThatThrownBy(() -> service.updateName(12L, "가".repeat(51))).isInstanceOf(ApiException.class);
        assertThat(user.getName()).hasSize(50);
    }

    @Test
    void missingUserReturnsNotFound() {
        when(repository.findById(12L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateName(12L, "새 이름")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
