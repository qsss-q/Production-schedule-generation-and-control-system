package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.CokeOvens;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ovens")
@RequiredArgsConstructor
public class CokeOvenController {

        private final CokeOvenRepository repository;

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
                return repository.save(oven);
        }

        @PutMapping("/{id}")
        public ResponseEntity<CokeOvens> update(@PathVariable Long id, @RequestBody CokeOvens oven) {
                if (!repository.existsById(id)) return ResponseEntity.notFound().build();
                oven.setId(id);
                return ResponseEntity.ok(repository.save(oven));
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<Void> delete(@PathVariable Long id) {
                if (!repository.existsById(id)) return ResponseEntity.notFound().build();
                repository.deleteById(id);
                return ResponseEntity.noContent().build();
        }
}