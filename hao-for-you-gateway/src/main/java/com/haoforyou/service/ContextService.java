package com.haoforyou.service;

import com.haoforyou.model.User;
import com.haoforyou.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContextService {

    private final UserRepository userRepository;

    public ContextService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 取得或建立 User（第一次訊息時自動建立） */
    @Transactional
    public User getOrCreateUser(String lineUserId) {
        return userRepository.findByLineUserId(lineUserId)
            .orElseGet(() -> userRepository.save(new User(lineUserId)));
    }
}
