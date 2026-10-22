// controller/EquipmentController.java
package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.Equipment;
import org.example.coke_factory.entity.EquipmentType;
import org.example.coke_factory.repository.EquipmentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentRepository repository;

    // все оборудование или только одного типа: /api/equipment?type=PUSHER
    @GetMapping
    public List<Equipment> getAll(@RequestParam(required = false) EquipmentType type) {
        return type == null ? repository.findAll() : repository.findByType(type);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Equipment> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Equipment create(@RequestBody Equipment equipment) {
        equipment.setId(null);
        return repository.save(equipment);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Equipment> update(@PathVariable Long id, @RequestBody Equipment equipment) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        equipment.setId(id);
        return ResponseEntity.ok(repository.save(equipment));
    }

    // переключатель доступности: PATCH /api/equipment/3/availability?available=false
    @PatchMapping("/{id}/availability")
    public ResponseEntity<Equipment> setAvailability(@PathVariable Long id,
                                                     @RequestParam boolean available) {
        return repository.findById(id)
                .map(e -> {
                    e.setAvailable(available);
                    return ResponseEntity.ok(repository.save(e));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
