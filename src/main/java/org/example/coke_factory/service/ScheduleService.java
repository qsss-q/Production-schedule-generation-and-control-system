package org.example.coke_factory.service;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.OperationStatus;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.repository.ScheduleOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * «Сформировать график»: берёт прогноз выдач у симулятора и сохраняет его
 * в БД как плановые операции (таблица schedule_operations).
 *
 * <p>Операции, назначенные оператором вручную (manual = true), не трогаем.
 * Сам алгоритм планирования (B1-B4) — зона Вани: когда он появится,
 * менять нужно только то, откуда берутся элементы графика.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** Длительность операции выдачи в графике, мин. */
    private static final int OPERATION_MINUTES = 30;

    private final SimulatorService simulator;
    private final ScheduleOperationRepository operations;
    private final CokeOvenRepository ovens;
    private final AuditService audit;

    @Transactional
    public SimulatorService.ScheduleDto generate(int limit) {
        SimulatorService.ScheduleDto schedule = simulator.generate(limit);

        // прошлый автоматический график заменяем новым
        operations.deleteByManualFalse();

        List<ScheduleOperation> created = new ArrayList<>();
        for (SimulatorService.FeedItemDto item : schedule.items()) {
            // печи в БД названы так же, как в справочнике: «Печь П-12»
            ovens.findByNameOven("Печь П-" + item.ovenNumber()).ifPresent(oven -> {
                LocalDateTime start = toPlantTime(item.plannedAt());

                ScheduleOperation op = new ScheduleOperation();
                op.setOven(oven);
                op.setOperationType("PUSHING");
                op.setPlannedStart(start);
                op.setPlannedEnd(start.plusMinutes(OPERATION_MINUTES));
                op.setStatus(OperationStatus.PLANNED);
                op.setPriority(1);
                op.setManual(false);
                op.setUrgent(false);
                op.setDeviationMinutes((int) Math.round(item.deviationMin()));
                created.add(op);
            });
        }
        operations.saveAll(created);

        audit.log("GENERATE_SCHEDULE", "ScheduleOperation", null,
                "Сформирован график: %d операций (алгоритм %s)"
                        .formatted(created.size(), schedule.algorithmCode()));

        return schedule;
    }

    private static LocalDateTime toPlantTime(String isoInstant) {
        return LocalDateTime.ofInstant(Instant.parse(isoInstant), SimulatorService.PLANT_ZONE);
    }
}
