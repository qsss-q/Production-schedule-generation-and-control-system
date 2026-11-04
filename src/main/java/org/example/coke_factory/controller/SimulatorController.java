package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.dto.ApiResponse;
import org.example.coke_factory.entity.AuditLog;
import org.example.coke_factory.service.AuditService;
import org.example.coke_factory.service.ScheduleService;
import org.example.coke_factory.service.SimulatorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Эндпоинты живой симуляции батареи: контракт Вани (docs/08), база /api/v1,
 * ответы в конверте { success, data, error }.
 *
 * <p>Чтение (печи, машины, лента, события, состояние) идёт из симулятора в памяти.
 * Действия оператора (управление, отказ, настройки, график) дополнительно
 * записываются в журнал действий в БД.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SimulatorController {

    private final SimulatorService simulator;
    private final ScheduleService schedule;
    private final AuditService audit;

    // ---------------- чтение ----------------

    @GetMapping("/battery")
    public ApiResponse<SimulatorService.BatteryDto> battery() {
        return ApiResponse.ok(simulator.battery());
    }

    @GetMapping("/state")
    public ApiResponse<SimulatorService.StateDto> state() {
        return ApiResponse.ok(simulator.state());
    }

    @GetMapping("/ovens")
    public ApiResponse<List<SimulatorService.OvenDto>> ovens(
            @RequestParam(defaultValue = "battery") String zoom) {
        if (!"battery".equals(zoom)) {
            throw new IllegalArgumentException("пока поддерживается zoom=battery");
        }
        return ApiResponse.ok(simulator.ovens());
    }

    @GetMapping("/machines")
    public ApiResponse<List<SimulatorService.MachineDto>> machines() {
        return ApiResponse.ok(simulator.machines());
    }

    @GetMapping("/feed")
    public ApiResponse<SimulatorService.FeedDto> feed(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(simulator.feed(limit));
    }

    @GetMapping("/events")
    public ApiResponse<List<SimulatorService.EventDto>> events(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(simulator.events(limit));
    }

    // ---------------- управление ----------------

    @PostMapping("/simulator/control")
    public ApiResponse<SimulatorService.StateDto> control(
            @RequestBody SimulatorService.ControlRequest request) {
        SimulatorService.StateDto state = simulator.control(request);
        audit.log("SIMULATOR_" + request.action().toUpperCase(), "Simulator", null,
                controlText(request, state));
        return ApiResponse.ok(state);
    }

    @PutMapping("/simulator/config")
    public ApiResponse<SimulatorService.StateDto> configure(
            @RequestBody SimulatorService.ConfigRequest request) {
        SimulatorService.StateDto state = simulator.configure(request);
        audit.log("SIMULATOR_CONFIG", "Simulator", null, configText(request, state));
        return ApiResponse.ok(state);
    }

    @PostMapping("/simulator/failure")
    public ApiResponse<SimulatorService.StateDto> failure(
            @RequestBody SimulatorService.FailureRequest request) {
        SimulatorService.StateDto state = simulator.inject(request);
        String where = request.ovenNumber() == null ? "" : " (П-" + request.ovenNumber() + ")";
        audit.log("SIMULATOR_FAILURE", "Simulator", null,
                "Сгенерирован отказ: " + request.type() + where);
        return ApiResponse.ok(state);
    }

    // ---------------- график ----------------

    @PostMapping("/schedule/generate")
    public ApiResponse<SimulatorService.ScheduleDto> generate(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(schedule.generate(limit));
    }

    // ---------------- журнал действий ----------------

    /** Фронт сообщает о действиях, которые не проходят через бэкенд симулятора
     *  (например, смена шага между выдачами или доступности оборудования). */
    @PostMapping("/operator-actions")
    public ApiResponse<AuditLog> operatorAction(@RequestBody OperatorActionRequest request) {
        if (request.summary() == null || request.summary().isBlank()) {
            throw new IllegalArgumentException("summary обязателен");
        }
        String action = request.action() == null || request.action().isBlank()
                ? "UI_ACTION" : request.action();
        return ApiResponse.ok(audit.log(action, request.entityName(), request.entityId(),
                request.summary()));
    }

    public record OperatorActionRequest(String action, String summary,
                                        String entityName, Long entityId) {
    }

    // ---------------- тексты для журнала ----------------

    private static String controlText(SimulatorService.ControlRequest r,
                                      SimulatorService.StateDto state) {
        return switch (r.action()) {
            case "start" -> "Запуск симулятора (x%d)".formatted(state.speedFactor());
            case "pause" -> "Симулятор поставлен на паузу";
            case "step" -> "Шаг симулятора: +%d мин".formatted(r.minutes() == null ? 1 : r.minutes());
            case "reset" -> "Симулятор остановлен и сброшен";
            default -> r.action();
        };
    }

    private static String configText(SimulatorService.ConfigRequest r,
                                     SimulatorService.StateDto state) {
        StringBuilder sb = new StringBuilder("Параметры симулятора:");
        if (r.speedFactor() != null) sb.append(" ускорение x").append(state.speedFactor());
        if (r.pushJamProb() != null) sb.append(" вероятность отказа выталкивания ")
                .append(Math.round(state.pushJamProb() * 1000) / 10.0).append(" %");
        if (r.ovenFaultPerHour() != null) sb.append(" отказы обогрева ")
                .append(state.ovenFaultPerHour()).append(" /печь/ч");
        if (r.seed() != null) sb.append(" seed ").append(state.seed());
        return sb.toString();
    }
}
