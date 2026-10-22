package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.ScheduleSettings;
import org.example.coke_factory.repository.ScheduleSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class ScheduleSettingsController {

    private final ScheduleSettingsRepository repository;

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
        settings.setId(null);
        return repository.save(settings);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduleSettings> update(@PathVariable Long id,
                                                   @RequestBody ScheduleSettings settings) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        settings.setId(id);
        return ResponseEntity.ok(repository.save(settings));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}