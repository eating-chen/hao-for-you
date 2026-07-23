package com.haoforyou.service;

import com.haoforyou.model.User;
import com.haoforyou.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContextService} 的單元測試。
 *
 * <p>{@code ContextService} 是純邏輯的 {@code @Service}，唯一的建構子依賴是
 * {@link UserRepository}，因此用 {@code @ExtendWith(MockitoExtension.class)} +
 * {@code @Mock} UserRepository + {@code @InjectMocks} 受測物件，全程不打真正的資料庫。</p>
 *
 * <p>本專案沒有 docs/specs 底下與 ContextService／getOrCreateUser 相關的設計文件
 * （docs/specs 目前僅有 Google API endpoints 的設計文件），因此測試依 {@code getOrCreateUser}
 * 現有實作行為撰寫：找得到既有使用者就直接回傳、找不到就用 {@code new User(lineUserId)}
 * 建立並儲存。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContextServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ContextService contextService;

    private static final String LINE_USER_ID = "U1234567890abcdef";

    // ────────────────────────────────────────────────────────────
    // getOrCreateUser — 使用者已存在（happy path）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreateUser - 使用者已存在")
    class ExistingUser {

        @Test
        @DisplayName("找得到既有使用者時，直接回傳該使用者，且不會呼叫 save")
        void getOrCreateUser_whenUserExists_returnsExistingUserWithoutSaving() {
            User existing = new User(LINE_USER_ID);
            existing.setId(42L);
            existing.setDisplayName("小明");
            when(userRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.of(existing));

            User result = contextService.getOrCreateUser(LINE_USER_ID);

            assertThat(result).isSameAs(existing);
            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getDisplayName()).isEqualTo("小明");
            verify(userRepository, times(1)).findByLineUserId(LINE_USER_ID);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("重複呼叫同一個 lineUserId 時，每次都只查詢、不建立新使用者")
        void getOrCreateUser_calledMultipleTimes_neverCreatesDuplicate() {
            User existing = new User(LINE_USER_ID);
            existing.setId(1L);
            when(userRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.of(existing));

            contextService.getOrCreateUser(LINE_USER_ID);
            contextService.getOrCreateUser(LINE_USER_ID);

            verify(userRepository, times(2)).findByLineUserId(LINE_USER_ID);
            verify(userRepository, never()).save(any(User.class));
        }
    }

    // ────────────────────────────────────────────────────────────
    // getOrCreateUser — 使用者不存在，第一次訊息時自動建立
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreateUser - 使用者不存在（自動建立）")
    class NewUserCreation {

        @Test
        @DisplayName("找不到使用者時，會以該 lineUserId 建立新 User 並呼叫 save，回傳 save 後的結果")
        void getOrCreateUser_whenUserNotFound_createsAndSavesNewUser() {
            when(userRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            User saved = new User(LINE_USER_ID);
            saved.setId(100L);
            when(userRepository.save(any(User.class))).thenReturn(saved);

            User result = contextService.getOrCreateUser(LINE_USER_ID);

            assertThat(result).isSameAs(saved);
            assertThat(result.getId()).isEqualTo(100L);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(1)).save(captor.capture());
            User toBeSaved = captor.getValue();
            assertThat(toBeSaved.getLineUserId()).isEqualTo(LINE_USER_ID);
            assertThat(toBeSaved.getId()).isNull();
        }

        @Test
        @DisplayName("找不到使用者時只呼叫一次 findByLineUserId 與一次 save（orElseGet 惰性求值）")
        void getOrCreateUser_whenUserNotFound_callsFindAndSaveExactlyOnce() {
            when(userRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenReturn(new User(LINE_USER_ID));

            contextService.getOrCreateUser(LINE_USER_ID);

            verify(userRepository, times(1)).findByLineUserId(LINE_USER_ID);
            verify(userRepository, times(1)).save(any(User.class));
        }
    }

    // ────────────────────────────────────────────────────────────
    // getOrCreateUser — null / 空值處理
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreateUser - null / 空值處理")
    class NullAndEmptyHandling {

        @Test
        @DisplayName("lineUserId 為 null 時，原樣傳給 repository 查詢；查無資料則建立 lineUserId 為 null 的新使用者")
        void getOrCreateUser_withNullLineUserId_passesNullThroughAndCreatesUser() {
            when(userRepository.findByLineUserId(eq((String) null))).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = contextService.getOrCreateUser(null);

            assertThat(result).isNotNull();
            assertThat(result.getLineUserId()).isNull();

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getLineUserId()).isNull();
        }

        @Test
        @DisplayName("lineUserId 為空字串時，視為一般字串查詢；查無資料則建立空字串 lineUserId 的新使用者")
        void getOrCreateUser_withEmptyLineUserId_createsUserWithEmptyId() {
            when(userRepository.findByLineUserId("")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = contextService.getOrCreateUser("");

            assertThat(result.getLineUserId()).isEmpty();
            verify(userRepository).findByLineUserId("");
            verify(userRepository).save(any(User.class));
        }
    }

    // ────────────────────────────────────────────────────────────
    // getOrCreateUser — 例外處理路徑
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreateUser - 例外處理")
    class ExceptionHandling {

        @Test
        @DisplayName("findByLineUserId 拋出例外時，例外會直接往上拋，且不會嘗試呼叫 save")
        void getOrCreateUser_whenFindThrows_propagatesExceptionWithoutSaving() {
            when(userRepository.findByLineUserId(LINE_USER_ID))
                    .thenThrow(new RuntimeException("資料庫連線失敗"));

            assertThatThrownBy(() -> contextService.getOrCreateUser(LINE_USER_ID))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("資料庫連線失敗");

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("save 拋出例外（例如 unique constraint 違反）時，例外會直接往上拋")
        void getOrCreateUser_whenSaveThrows_propagatesException() {
            when(userRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> contextService.getOrCreateUser(LINE_USER_ID))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessage("duplicate key");

            verify(userRepository, times(1)).findByLineUserId(LINE_USER_ID);
            verify(userRepository, times(1)).save(any(User.class));
        }
    }
}
