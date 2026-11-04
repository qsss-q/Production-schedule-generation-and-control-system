package org.example.coke_factory.exception;

import org.example.coke_factory.controller.SimulatorController;
import org.example.coke_factory.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ошибки эндпоинтов симулятора (/api/v1) в формате конверта { success:false, error }.
 * Остальные контроллеры (/api/...) этот обработчик не затрагивает.
 */
@RestControllerAdvice(assignableTypes = SimulatorController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(IllegalArgumentException e) {
        return ApiResponse.fail("VALIDATION_FAILED", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> internal(Exception e) {
        return ApiResponse.fail("INTERNAL_ERROR", e.getMessage());
    }
}
