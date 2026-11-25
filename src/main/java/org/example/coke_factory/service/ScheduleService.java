package org.example.coke_factory.service;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.repository.ScheduleOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * «Сформировать график»: реальный прогон планировщика ({@link ScheduleRunService})
 * от текущего состояния батареи. План устанавливается в симулятор, операции
 * получают живые статусы, история прогонов сохраняется — прошлый график
 * больше не стирается.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRunService runService;
    private final ScheduleOperationRepository operations;

    @Transactional
    public SimulatorService.ScheduleDto generate(int limit) {
        ScheduleRunService.RunDto run = runService.createRun(
                new ScheduleRunService.CreateRunRequest(null, null, null, null, null, null),
                "формирование графика оператором");

        List<ScheduleOperation> ops = operations.findByRunIdOrderByIdAsc(run.id());
        List<SimulatorService.FeedItemDto> items = ops.stream()
                .filter(op -> !Boolean.TRUE.equals(op.getManual()))
                .limit(Math.max(1, limit))
                .map(op -> new SimulatorService.FeedItemDto(
                        op.getOvenNumber(), "discharge_start",
                        "П-%d · выдача кокса".formatted(op.getOvenNumber()),
                        iso(op.getPlannedStart()), iso(op.getPlannedStart()),
                        op.getDeviationMinutes() == null ? 0 : op.getDeviationMinutes(),
                        "info", "по графику"))
                .toList();
        return new SimulatorService.ScheduleDto(run.createdAt(), run.algorithm(),
                run.totalOperations(), items);
    }

    private static String iso(LocalDateTime plantTime) {
        return plantTime.atZone(SimulatorService.PLANT_ZONE).toInstant().toString();
    }
}
