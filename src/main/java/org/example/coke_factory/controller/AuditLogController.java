package org.example.coke_factory.controller;
//только на чтнение и добавление
import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.AuditLog;
import org.example.coke_factory.repository.AuditLogRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository repository;

    @GetMapping
    public List<AuditLog> getAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/entity")
    public List<AuditLog> getByEntity(@RequestParam String name, @RequestParam Long id) {
        return repository.findByEntityNameAndEntityIdOrderByCreatedAtDesc(name, id);
    }

    @PostMapping
    public AuditLog create(@RequestBody AuditLog log) {
        log.setId(null);
        return repository.save(log);
    }
}