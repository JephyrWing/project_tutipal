package com.my.tutipal_back.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.users.dto.SocialProfile;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.AccountLocks;
import com.my.tutipal_back.users.repository.UserRepository;

/**
 * PM 2026-11-26 (PT-13): a withdrawn account keeps its row as provider='withdrawn', provider_id=its own id,
 * name='탈퇴한 사용자'. It acts like a missing user, its old social account cannot reach it, and kept records show
 * the anonymized name.
 */
class UserServiceWithdrawnTest {

    private UserRepository repository;
    private AccountLocks locks;
    private UserService service;
    private User withdrawn;

    @BeforeEach
    void setup() {
        repository = mock(UserRepository.class);
        locks = mock(AccountLocks.class);
        service = new UserService(repository, locks);
        // the row as AccountCleanup.ANONYMIZE leaves it (was google / social-1 / 가입 이름)
        withdrawn = new User(User.WITHDRAWN_PROVIDER, "12", User.WITHDRAWN_NAME);
        ReflectionTestUtils.setField(withdrawn, "id", 12L);
        when(repository.findById(12L)).thenReturn(Optional.of(withdrawn));
    }

    @Test
    void signingUpAgainWithTheSameSocialAccountCreatesANewUser() {
        // social login looks users up by (provider, provider_id); the anonymized row no longer has google / social-1
        when(repository.findByProviderAndProviderId("google", "social-1")).thenReturn(Optional.empty());
        when(repository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 13L);
            return saved;
        });

        User again = service.upsert(new SocialProfile("google", "social-1", "다시 가입"));

        assertThat(again.getId()).isEqualTo(13L).isNotEqualTo(withdrawn.getId());
        assertThat(again.getProvider()).isEqualTo("google");
        assertThat(again.getName()).isEqualTo("다시 가입");
        assertThat(again.isWithdrawn()).isFalse();
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(withdrawn);
        assertThat(withdrawn.getName()).isEqualTo(User.WITHDRAWN_NAME);   // the old row stays anonymized
    }

    @Test
    void noSocialLoginCanProduceTheWithdrawnProvider() {
        assertThatThrownBy(() -> SocialProfile.of(User.WITHDRAWN_PROVIDER, Map.of("id", "12")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getMeOfAWithdrawnUserIs404() {
        assertThatThrownBy(() -> service.getMe(12L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getUserOfAWithdrawnUserIs404SoNoNewLoginSessionIsMade() {
        // AppLoginController.exchange: a code issued before the withdrawal must not log the account in again
        assertThatThrownBy(() -> service.getUser(12L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void renamingAWithdrawnUserIs404() {
        assertThatThrownBy(() -> service.updateName(12L, "새 이름"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(withdrawn.getName()).isEqualTo(User.WITHDRAWN_NAME);
    }

    @Test
    void keptRecordsShowTheAnonymizedName() {
        User active = new User("kakao", "k-1", "김학생");
        ReflectionTestUtils.setField(active, "id", 14L);
        when(repository.findAllById(List.of(12L, 14L))).thenReturn(List.of(withdrawn, active));

        assertThat(service.namesOf(List.of(12L, 14L))).containsEntry(12L, "탈퇴한 사용자").containsEntry(14L, "김학생");
    }

    @Test
    void creatingPathsAreRefusedWith401ForAWithdrawnAccount() {
        when(locks.lockActive(12L)).thenReturn(false);

        assertThatThrownBy(() -> service.lockActiveAccount(12L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void activeAccountPassesTheGate() {
        when(locks.lockActive(14L)).thenReturn(true);

        service.lockActiveAccount(14L);

        verify(locks).lockActive(14L);
    }
}
