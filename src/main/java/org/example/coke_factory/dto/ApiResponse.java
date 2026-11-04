package org.example.coke_factory.dto;

/**
 * Единый конверт ответа для эндпоинтов симулятора (/api/v1/...),
 * как в контракте Вани (docs/08): { success, data, error }.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message));
    }

    public record ApiError(String code, String message) {
    }
}
