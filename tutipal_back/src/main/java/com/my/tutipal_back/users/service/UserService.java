package com.my.tutipal_back.users.service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.users.dto.SocialProfile;
import com.my.tutipal_back.users.dto.UserResponse;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.AccountLocks;
import com.my.tutipal_back.users.repository.UserRepository;
import com.my.tutipal_back.common.exception.ApiException;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final AccountLocks accountLocks;

    public UserService(UserRepository userRepository, AccountLocks accountLocks) {
        this.userRepository = userRepository;
        this.accountLocks = accountLocks;
    }

    /**
     * 새 기록을 여는 경로(수업 만들기 · 참가 · 도움 요청)의 첫 잠금. 이 사용자 행을 FOR SHARE 로 잠가 회원 탈퇴와 직렬화하고,
     * 탈퇴했거나 없는 계정이면 401 (로그인 세션은 탈퇴 커밋 뒤 끊기므로 이미 인증을 통과한 요청만 여기까지 온다).
     * 수업 행보다 먼저 불러야 한다 (탈퇴도 users 행 → 수업 행 순서, AccountLocks 참고).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActiveAccount(Long userId) {
        if (!accountLocks.lockActive(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "탈퇴했거나 없는 계정이에요. 다시 로그인해 주세요");
        }
    }

    /** 소셜 이름은 최초 가입 때만 사용하며, 재로그인은 저장된 앱 이름을 유지한다. */
    @Transactional
    public User upsert(SocialProfile profile) {
        return userRepository.findByProviderAndProviderId(profile.provider(), profile.providerId())
                .orElseGet(() -> userRepository.save(new User(
                        profile.provider(), profile.providerId(), profile.displayName())));
    }

    /**
     * 다른 도메인(auth)용: 엔티티 그대로 (세션 로그인 만들 때). 탈퇴한 계정은 없는 사용자처럼 404 (앱 로그인 교환 코드가
     * 탈퇴 전에 발급됐어도 새 로그인 세션을 만들지 않게).
     */
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isWithdrawn())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }

    public UserResponse getMe(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isWithdrawn())
                .map(UserResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }

    @Transactional
    public UserResponse updateName(Long userId, String name) {
        String normalized = name == null ? "" : name.replaceAll("(?U)^\\s+|\\s+$", "");
        if (normalized.isBlank() || normalized.length() > 50
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "이름은 제어문자 없이 1~50자로 입력해 주세요");
        }
        User user = getUser(userId);
        user.rename(normalized);
        return UserResponse.from(user);
    }

    /** 다른 도메인(세션 참가자 목록 등)용: id → 이름. */
    public Map<Long, String> namesOf(Collection<Long> ids) {
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getName));
    }
}
