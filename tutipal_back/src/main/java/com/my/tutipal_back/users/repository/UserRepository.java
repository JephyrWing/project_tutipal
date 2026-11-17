package com.my.tutipal_back.users.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.my.tutipal_back.users.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderId(String provider, String providerId);
}
