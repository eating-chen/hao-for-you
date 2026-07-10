package com.haoforyou.config;

import com.haoforyou.model.User;
import com.haoforyou.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
public class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String TEST_LINE_USER_ID = "UtestUser123456789";

    @Bean
    CommandLineRunner seedTestUser(UserRepository userRepository) {
        return args -> {
            log.info("[DevDataSeeder] 新增測試使用者 (line_user_id={})", TEST_LINE_USER_ID);
            try {
                if (userRepository.findByLineUserId(TEST_LINE_USER_ID).isEmpty()) {
                    User testUser = new User(TEST_LINE_USER_ID);
                    testUser.setDisplayName("測試使用者");
                    userRepository.save(testUser);
                    log.info("[DevDataSeeder] 已新增測試使用者 id={}", testUser.getId());
                } else {
                    log.info("[DevDataSeeder] 測試使用者已存在");
                }
            } catch (Exception e) {
                log.error("[DevDataSeeder] 新增測試使用者失敗，請確認 users table 是否已建立或資料庫連線設定是否正確", e);
                throw e;
            }
        };
    }
}
