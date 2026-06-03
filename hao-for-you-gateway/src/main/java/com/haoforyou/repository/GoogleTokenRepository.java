package com.haoforyou.repository;

import com.haoforyou.model.GoogleToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GoogleTokenRepository extends JpaRepository<GoogleToken, Long> {
    Optional<GoogleToken> findByLineUserId(String lineUserId);
    boolean existsByLineUserId(String lineUserId);
    void deleteByLineUserId(String lineUserId);
}
