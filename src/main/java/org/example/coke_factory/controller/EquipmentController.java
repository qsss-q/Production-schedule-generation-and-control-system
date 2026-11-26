// controller/EquipmentController.java
package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.Equipment;
import org.example.coke_factory.entity.EquipmentType;
import org.example.coke_factory.repository.EquipmentRepository;
import org.example.coke_factory.service.AuditService;
import org.example.coke_factory.service.SimulatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentRepository repository;
    private final SimulatorService simulator;
    private final AuditService audit;

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
        Equipment saved = repository.save(equipment);
        audit.log("EQUIPMENT_CREATE", "Equipment", saved.getId(), "Добавлено оборудование: " + saved.getName());
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<Equipment> update(@PathVariable Long id, @RequestBody Equipment equipment) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        equipment.setId(id);
        Equipment saved = repository.save(equipment);
        audit.log("EQUIPMENT_UPDATE", "Equipment", id, "Изменено оборудование: " + saved.getName());
        return ResponseEntity.ok(saved);
    }

    // переключатель доступности: PATCH /api/equipment/3/availability?available=false
    // меняет и справочник, и живой прогон: печь — вывод из серии (слот пропускается),
    // машина — простой до возврата
    @PatchMapping("/{id}/availability")
    public ResponseEntity<Equipment> setAvailability(@PathVariable Long id,
                                                     @RequestParam boolean available) {
        return repository.findById(id)
                .map(e -> {
                    e.setAvailable(available);
                    Equipment saved = repository.save(e);
                    applyToSimulator(e, available);
                    audit.log("EQUIPMENT_AVAILABILITY", "Equipment", id,
                            "%s: %s".formatted(e.getName(), available ? "доступно" : "выведено"));
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void applyToSimulator(Equipment equipment, boolean available) {
        String name = equipment.getName() == null ? "" : equipment.getName();
        if (equipment.getType() == EquipmentType.OVEN && name.startsWith("Печь П-")) {
            try {
                simulator.setOvenAvailable(
                        Integer.parseInt(name.substring("Печь П-".length())), available, "справочник");
            } catch (NumberFormatException ignored) {
                // имя вне формата батареи — только справочник
            }
        } else {
            ru.evraz.koks.sim.MachineKind kind = machineKind(equipment.getType());
            if (kind != null) {
                if (available) simulator.setMachineUp(kind);
                else simulator.setMachineDown(kind, 0);
            }
        }
    }

    private static ru.evraz.koks.sim.MachineKind machineKind(EquipmentType type) {
        return switch (type) {
            case PUSHER -> ru.evraz.koks.sim.MachineKind.PUSHING;
            case QUENCHING_CAR -> ru.evraz.koks.sim.MachineKind.QUENCH_CAR;
            case DOOR_MACHINE -> ru.evraz.koks.sim.MachineKind.DOOR_MACHINE;
            case LOADING_CAR -> ru.evraz.koks.sim.MachineKind.LOADING_CAR;
            default -> null;
        };
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        audit.log("EQUIPMENT_DELETE", "Equipment", id, "Удалено оборудование");
        return ResponseEntity.noContent().build();
    }
}
