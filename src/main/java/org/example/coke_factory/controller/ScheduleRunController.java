package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.dto.ApiResponse;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.service.ScheduleRunService;
import org.example.coke_factory.service.SimulatorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.evraz.koks.sim.MachineKind;

import java.util.List;
import java.util.Locale;

/**
 * Прогоны планировщика (docs/03 §3, docs/09 §8): формирование графика,
 * версии, живые статусы, ручная правка с причиной, доступность оборудования
 * и сравнение алгоритмов на одном входе.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScheduleRunController {

    private final ScheduleRunService runs;
    private final SimulatorService simulator;

    // ---------------- прогоны ----------------

    @PostMapping("/schedule-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScheduleRunService.RunDto> createRun(
            @RequestBody ScheduleRunService.CreateRunRequest request) {
        return ApiResponse.ok(runs.createRun(request, "формирование графика"));
    }

    @GetMapping("/schedule-runs")
    public ApiResponse<List<ScheduleRunService.RunDto>> listRuns() {
        return ApiResponse.ok(runs.list());
    }

    @GetMapping("/schedule-runs/active")
    public ApiResponse<ScheduleRunService.RunDto> activeRun() {
        return ApiResponse.ok(runs.activeRun().orElse(null));
    }

    @GetMapping("/schedule-runs/{id}")
    public ApiResponse<ScheduleRunService.RunDto> getRun(@PathVariable String id) {
        return ApiResponse.ok(runs.get(id));
    }

    @GetMapping("/schedule-runs/{id}/operations")
    public ApiResponse<List<ScheduleOperation>> runOperations(@PathVariable String id) {
        return ApiResponse.ok(runs.runOperations(id));
    }

    @PostMapping("/schedule-runs/compare")
    public ApiResponse<ScheduleRunService.CompareResult> compare(
            @RequestBody ScheduleRunService.CompareRequest request) {
        return ApiResponse.ok(runs.compare(request));
    }

    // ---------------- ручная правка (docs/03 PATCH) ----------------

    @PatchMapping("/schedule-operations/{id}")
    public ApiResponse<ScheduleOperation> manualEdit(
            @PathVariable long id,
            @RequestBody ScheduleRunService.ManualEditRequest request) {
        return ApiResponse.ok(runs.manualEdit(id, request));
    }

    // ---------------- доступность оборудования (docs/10) ----------------

    /** Вывести печь из серии или вернуть: слот пропускается, порядок не меняется. */
    @PostMapping("/ovens/{ovenNumber}/availability")
    public ApiResponse<AvailabilityDto> ovenAvailability(
            @PathVariable int ovenNumber,
            @RequestBody OvenAvailabilityRequest request) {
        simulator.setOvenAvailable(ovenNumber, request.available(), request.reason());
        return ApiResponse.ok(new AvailabilityDto(
                "П-%d".formatted(ovenNumber), request.available(), simulator.revision()));
    }

    public record OvenAvailabilityRequest(boolean available, String reason) { }

    public record AvailabilityDto(String target, boolean available, long revision) { }

    /** Простой машины: на minutes минут или до возврата (minutes <= 0). */
    @PostMapping("/machines/{code}/down")
    public ApiResponse<AvailabilityDto> machineDown(
            @PathVariable String code,
            @RequestBody MachineDownRequest request) {
        MachineKind kind = machineKind(code);
        simulator.setMachineDown(kind, request.minutes() == null ? 0 : request.minutes());
        return ApiResponse.ok(new AvailabilityDto(kind.code, false, simulator.revision()));
    }

    public record MachineDownRequest(Double minutes) { }

    /** Вернуть машину в работу. */
    @PostMapping("/machines/{code}/up")
    public ApiResponse<AvailabilityDto> machineUp(@PathVariable String code) {
        MachineKind kind = machineKind(code);
        simulator.setMachineUp(kind);
        return ApiResponse.ok(new AvailabilityDto(kind.code, true, simulator.revision()));
    }

    /** Код машины: имя вида PUSHING или машинный код КВ/ДСМ/ТВ/УВ. */
    private static MachineKind machineKind(String code) {
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        for (MachineKind kind : MachineKind.values()) {
            if (kind.name().equals(normalized) || kind.code.equals(code.strip())) return kind;
        }
        throw new IllegalArgumentException(
                "неизвестная машина %s, доступно: КВ, ДСМ, ТВ, УВ".formatted(code));
    }
}
