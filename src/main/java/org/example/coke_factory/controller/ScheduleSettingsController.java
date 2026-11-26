package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.ScheduleSettings;
import org.example.coke_factory.repository.ScheduleSettingsRepository;
import org.example.coke_factory.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class ScheduleSettingsController {

    private final ScheduleSettingsRepository repository;
    private final AuditService audit;

    @GetMapping
    public List<ScheduleSettings> getAll() {
        return repository.findAll();
    }

    // текущие активные настройки
    @GetMapping("/active")
    public ResponseEntity<ScheduleSettings> getActive() {
        return repository.findFirstByActiveTrue()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ScheduleSettings create(@RequestBody ScheduleSettings settings) {
        validate(settings);
        settings.setId(null);
        if (Boolean.TRUE.equals(settings.getActive())) {
            repository.findAll().stream().filter(s -> Boolean.TRUE.equals(s.getActive()))
                    .forEach(s -> { s.setActive(false); repository.save(s); });
        }
        ScheduleSettings saved = repository.save(settings);
        audit.log("SETTINGS_CREATE", "ScheduleSettings", saved.getId(), "Созданы настройки расписания: " + saved.getName());
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduleSettings> update(@PathVariable Long id,
                                                   @RequestBody ScheduleSettings settings) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        validate(settings);
        settings.setId(id);
        if (Boolean.TRUE.equals(settings.getActive())) {
            repository.findAll().stream().filter(s -> Boolean.TRUE.equals(s.getActive()) && !s.getId().equals(id))
                    .forEach(s -> { s.setActive(false); repository.save(s); });
        }
        ScheduleSettings saved = repository.save(settings);
        audit.log("SETTINGS_UPDATE", "ScheduleSettings", id, "Обновлены настройки расписания: " + saved.getName());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        audit.log("SETTINGS_DELETE", "ScheduleSettings", id, "Удалены настройки расписания");
        return ResponseEntity.noContent().build();
    }

    private static void validate(ScheduleSettings settings) {
        if (settings.getDefaultPriority() == null) settings.setDefaultPriority(1);
        if (settings.getStepMinutes() != null && settings.getStepMinutes() <= 0)
            throw new IllegalArgumentException("шаг между операциями должен быть больше 0");
        if (settings.getMaxIdleMinutes() != null && settings.getMaxIdleMinutes() <= 0)
            throw new IllegalArgumentException("порог опоздания должен быть больше 0");
        int priority = settings.getDefaultPriority() == null ? 1 : settings.getDefaultPriority();
        if (priority < 1 || priority > 3)
            throw new IllegalArgumentException("приоритет по умолчанию должен быть 1, 2 или 3");
        if (settings.getShiftStart() != null && settings.getShiftEnd() != null) {
            if (settings.getShiftStart().equals(settings.getShiftEnd()))
                throw new IllegalArgumentException("начало и конец смены не могут совпадать");
            if (settings.getShiftStart().isAfter(settings.getShiftEnd()))
                throw new IllegalArgumentException("окно смены через полночь пока не поддерживается");
        }
    }
}