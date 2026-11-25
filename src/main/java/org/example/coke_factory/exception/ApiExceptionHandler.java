package org.example.coke_factory.exception;

import org.example.coke_factory.controller.ScheduleRunController;
import org.example.coke_factory.controller.SimulatorController;
import org.example.coke_factory.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ошибки эндпоинтов симулятора и прогонов (/api/v1) в формате конверта
 * { success:false, error }. Остальные контроллеры (/api/...) не затрагивает.
 */
@RestControllerAdvice(assignableTypes = {SimulatorController.class, ScheduleRunController.class})
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(IllegalArgumentException e) {
        return ApiResponse.fail("VALIDATION_FAILED", e.getMessage());
    }

    /**
     * Тело запроса не читается как JSON или не ложится на структуру — например,
     * в /simulator/state/import прислали не тот файл.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> unreadableBody(HttpMessageNotReadableException e) {
        String reason = e.getMostSpecificCause().getMessage();
        if (reason == null) reason = "тело запроса не разобрано";
        reason = reason.strip().replace('\n', ' ');
        if (reason.length() > 300) reason = reason.substring(0, 300) + "…";
        return ApiResponse.fail("UNREADABLE_BODY", "Файл не читается: " + reason);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> internal(Exception e) {
        return ApiResponse.fail("INTERNAL_ERROR", e.getMessage());
    }
}
