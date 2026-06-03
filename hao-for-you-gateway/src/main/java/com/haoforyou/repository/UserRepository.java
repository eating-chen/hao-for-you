package com.haoforyou.repository;

import com.haoforyou.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLineUserId(String lineUserId);
}
