package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.CokeOvens;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ovens")
@RequiredArgsConstructor
public class CokeOvenController {

        private final CokeOvenRepository repository;
        private final AuditService audit;

        @GetMapping
        public List<CokeOvens> getAll() {
                return repository.findAll();
        }

        @GetMapping("/{id}")
        public ResponseEntity<CokeOvens> getById(@PathVariable Long id) {
                return repository.findById(id)
                        .map(ResponseEntity::ok)
                        .orElse(ResponseEntity.notFound().build());
        }

        @PostMapping
        public CokeOvens create(@RequestBody CokeOvens oven) {
                oven.setId(null);
                CokeOvens saved = repository.save(oven);
                audit.log("OVEN_CREATE", "CokeOvens", saved.getId(), "Добавлена печь: " + saved.getNameOven());
                return saved;
        }

        @PutMapping("/{id}")
        public ResponseEntity<CokeOvens> update(@PathVariable Long id, @RequestBody CokeOvens oven) {
                if (!repository.existsById(id)) return ResponseEntity.notFound().build();
                oven.setId(id);
                CokeOvens saved = repository.save(oven);
                audit.log("OVEN_UPDATE", "CokeOvens", id, "Изменена печь: " + saved.getNameOven());
                return ResponseEntity.ok(saved);
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<Void> delete(@PathVariable Long id) {
                if (!repository.existsById(id)) return ResponseEntity.notFound().build();
                repository.deleteById(id);
                audit.log("OVEN_DELETE", "CokeOvens", id, "Удалена печь");
                return ResponseEntity.noContent().build();
        }
}