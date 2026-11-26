package com.my.tutipal_back.users.entity;

import org.hibernate.annotations.DynamicUpdate;

import com.my.tutipal_back.common.support.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 계정. 이름 변경과 탈퇴 익명화는 SQL 로만 바꾼다 (AccountLocks.renameIfActive, AccountCleanup.anonymize).
 * {@code @DynamicUpdate}: 나중에 엔티티를 고치는 코드가 생겨도 바뀐 컬럼만 써서, 오래된 엔티티가 익명화된 provider 를 되살리지 않게.
 */
@Entity
@DynamicUpdate
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "providerId"}))
public class User extends BaseTimeEntity {

    /** 탈퇴한 계정의 provider. 소셜 로그인 provider(google · naver · kakao · dev)와 겹치지 않아 다시 로그인할 수 없다. */
    public static final String WITHDRAWN_PROVIDER = "withdrawn";
    /** 탈퇴한 계정의 표시 이름. 남은 기록(참가자 · 글 · 답안 · 도움 채팅)에 이 이름이 보인다. */
    public static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String provider;        // google | naver | kakao | dev | withdrawn(탈퇴, AccountCleanup.anonymize)

    @Column(nullable = false, length = 100)
    private String providerId;      // 소셜 쪽 사용자 id

    // 이메일은 받지 않는다(PT-14). users.email 컬럼은 DB에 남겨 두지만 매핑하지 않는다(V13에서 기존 값 정리).

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    protected User() {
    }

    public User(String provider, String providerId, String name) {
        this.provider = provider;
        this.providerId = providerId;
        this.name = name;
        this.role = Role.USER;      // 선생님/학생은 세션마다 정해짐 (studysession)
    }

    /** 회원 탈퇴로 익명화된 계정. 없는 사용자처럼 다룬다 (PM 2026-11-26, PT-13). */
    public boolean isWithdrawn() {
        return WITHDRAWN_PROVIDER.equals(provider);
    }

    public Long getId() { return id; }
    public String getProvider() { return provider; }
    public String getName() { return name; }
    public Role getRole() { return role; }
}
