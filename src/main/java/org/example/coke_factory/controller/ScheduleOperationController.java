package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.OperationStatus;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.repository.ScheduleOperationRepository;
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

    @GetMapping
    public List<ScheduleOperation> getAll() {
        return repository.findAll();
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
        operation.setId(null);
        return repository.save(operation);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduleOperation> update(@PathVariable Long id,
                                                    @RequestBody ScheduleOperation operation) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        operation.setId(id);
        return ResponseEntity.ok(repository.save(operation));
    }

    // быстро сменить статус (запланировано -> выполняется и т. д.)
    @PatchMapping("/{id}/status")
    public ResponseEntity<ScheduleOperation> changeStatus(@PathVariable Long id,
                                                          @RequestParam OperationStatus status) {
        return repository.findById(id)
                .map(op -> {
                    op.setStatus(status);
                    return ResponseEntity.ok(repository.save(op));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
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