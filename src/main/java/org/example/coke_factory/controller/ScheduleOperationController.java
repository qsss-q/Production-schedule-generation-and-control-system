package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.OperationStatus;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.repository.ScheduleOperationRepository;
import org.example.coke_factory.service.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class ScheduleOperationController {

    private final ScheduleOperationRepository repository;
    private final AuditService audit;

    @GetMapping
    public List<ScheduleOperation> getAll(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Boolean manual) {
        List<ScheduleOperation> ops = runId != null && !runId.isBlank()
                ? repository.findByRunIdOrderByIdAsc(runId)
                : repository.findAll();
        if (manual != null)
            ops = ops.stream().filter(op -> manual.equals(op.getManual())).toList();
        return ops;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduleOperation> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/oven/{ovenId}")
    public List<ScheduleOperation> getByOven(@PathVariable Long ovenId) {
        return repository.findByOvenId(ovenId);
    }

    @GetMapping("/status/{status}")
    public List<ScheduleOperation> getByStatus(@PathVariable OperationStatus status) {
        return repository.findByStatus(status);
    }

    // для временной шкалы: операции в окне времени
    @GetMapping("/range")
    public List<ScheduleOperation> getInRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return repository.findByPlannedStartLessThanEqualAndPlannedEndGreaterThanEqual(to, from);
    }

    @PostMapping
    public ScheduleOperation create(@RequestBody ScheduleOperation operation) {
        validatePriority(operation.getPriority());
        operation.setId(null);
        ScheduleOperation saved = repository.save(operation);
        audit.log("OPERATION_CREATE", "ScheduleOperation", saved.getId(), "Создана операция графика");
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduleOperation> update(@PathVariable Long id,
                                                    @RequestBody ScheduleOperation operation) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        validatePriority(operation.getPriority());
        operation.setId(id);
        ScheduleOperation saved = repository.save(operation);
        audit.log("OPERATION_UPDATE", "ScheduleOperation", id, "Изменена операция графика");
        return ResponseEntity.ok(saved);
    }

    // быстро сменить статус (запланировано -> выполняется и т. д.)
    @PatchMapping("/{id}/status")
    public ResponseEntity<ScheduleOperation> changeStatus(@PathVariable Long id,
                                                          @RequestParam OperationStatus status) {
        return repository.findById(id)
                .map(op -> {
                    op.setStatus(status);
                    ScheduleOperation saved = repository.save(op);
                    audit.log("OPERATION_STATUS", "ScheduleOperation", id, "Статус операции изменён на " + status);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        audit.log("OPERATION_DELETE", "ScheduleOperation", id, "Удалена операция графика");
        return ResponseEntity.noContent().build();
    }

    /** Изменить приоритет операции: 1 — обычный, 2 — высокий, 3 — срочный. */
    @PatchMapping("/{id}/priority")
    public ResponseEntity<ScheduleOperation> changePriority(@PathVariable Long id,
                                                            @RequestParam int priority) {
        validatePriority(priority);
        return repository.findById(id).map(op -> {
            op.setPriority(priority);
            ScheduleOperation saved = repository.save(op);
            audit.log("OPERATION_PRIORITY", "ScheduleOperation", id,
                    "Приоритет операции изменён на " + priority);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    private static void validatePriority(Integer priority) {
        if (priority == null || priority < 1 || priority > 3)
            throw new IllegalArgumentException("приоритет операции должен быть 1, 2 или 3");
    }

    // лента ближайших событий: /api/operations/upcoming  или  ?from=2026-10-05T11:10:00
    @GetMapping("/upcoming")
    public List<ScheduleOperation> getUpcoming(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from) {
        if (from == null) from = LocalDateTime.now();
        return repository.findTop10ByPlannedStartAfterOrderByPlannedStartAsc(from);
    }
}