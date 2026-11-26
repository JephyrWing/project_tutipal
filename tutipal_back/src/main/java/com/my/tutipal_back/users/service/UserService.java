package com.my.tutipal_back.users.service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.users.dto.SocialProfile;
import com.my.tutipal_back.users.dto.UserResponse;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.repository.UserRepository;
import com.my.tutipal_back.common.exception.ApiException;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
