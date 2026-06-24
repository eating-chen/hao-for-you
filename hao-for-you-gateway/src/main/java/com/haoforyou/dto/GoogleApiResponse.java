package com.haoforyou.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
public class GoogleApiResponse<T> {

    private final boolean success;
    private final T data;
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
