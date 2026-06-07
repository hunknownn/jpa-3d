package com.example.shop.repository;

import com.example.shop.user.User;
import java.util.Optional;

public interface UserRepository extends AuditableRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);
}
