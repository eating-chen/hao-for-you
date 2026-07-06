package com.haoforyou.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
public class GoogleApiResponse<T> {

   @Schema(description = "是否執行成功", example = "true") 
    private final boolean success;

    @Schema(description = "依執行結果回傳所需資訊，若 success 為 false 則為 null")
    private final T data;

    @Schema(description = "錯誤訊息，若 success 為 true 則為 null")
    private final ErrorDetail error;

    private GoogleApiResponse(boolean success, T data, ErrorDetail error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> GoogleApiResponse<T> ok(T data) {
        return new GoogleApiResponse<>(true, data, null);
    }

    public static <T> GoogleApiResponse<T> fail(String code, String message) {
        return new GoogleApiResponse<>(false, null, new ErrorDetail(code, message));
    }

    @Getter
    @AllArgsConstructor
    public static class ErrorDetail {
        private final String code;
        private final String message;
    }
}
