package org.example.coke_factory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.coke_factory.entity.CokeOvens;
import org.example.coke_factory.entity.Equipment;
import org.example.coke_factory.entity.EquipmentType;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.repository.EquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.evraz.koks.sim.MachineKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Импорт справочников из CSV (docs/04 §1–3) и выгрузка результатов прогона.
 * Формат: строки с «;», первая строка — заголовок. Печи и машины обновляются
 * в справочниках без удаления связанных с ними строк; симулятор пересобирается
 * под новый состав печей. Статусы repair/failed трактуются как недоступность.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectoryCsvService {

    private final CokeOvenRepository ovens;
    private final EquipmentRepository equipment;
    private final SimulatorService simulator;
    private final AuditService audit;

    /** Обновить справочник печей из ovens.csv (docs/04 §2): battery_code;number;status;… */
    @Transactional
    public ImportResult importOvens(String csv) {
        List<String[]> rows = parse(csv);
        if (rows.isEmpty())
            throw new IllegalArgumentException("файл печей пуст");
        List<CokeOvens> fresh = new ArrayList<>();
        List<Equipment> equipmentToSave = new ArrayList<>();
        java.util.Set<Integer> seenNumbers = new java.util.HashSet<>();
        java.util.Set<String> importedOvenNames = new java.util.HashSet<>();
        int maxNumber = 0;
        String batteryCode = null;
        List<Integer> unavailable = new ArrayList<>();

        // Не удаляем существующие строки: на них могут ссылаться операции,
        // температурные измерения и уведомления. Обновляем записи по имени.
        List<CokeOvens> existingOvens = ovens.findAll();
        java.util.Map<String, CokeOvens> ovenByName = new java.util.HashMap<>();
        existingOvens.forEach(o -> ovenByName.put(o.getNameOven(), o));
        List<Equipment> existingEquipment = equipment.findByType(EquipmentType.OVEN);
        java.util.Map<String, Equipment> equipmentByName = new java.util.HashMap<>();
        existingEquipment.forEach(e -> equipmentByName.put(e.getName(), e));

        for (String[] row : rows) {
            // battery_code;number;status;… — остальные колонки справочные
            if (row.length < 3)
                throw new IllegalArgumentException(
                        "в строке печи меньше трёх колонок: " + String.join(";", row));
            if (batteryCode == null) batteryCode = row[0].strip();
            else if (!batteryCode.equals(row[0].strip()))
                throw new IllegalArgumentException("один CSV-файл должен содержать печи одной батареи");
            int number = Integer.parseInt(row[1].strip());
            if (number < 1) throw new IllegalArgumentException("номер печи должен быть ≥ 1: " + number);
            if (!seenNumbers.add(number))
                throw new IllegalArgumentException("номер печи повторяется в CSV: " + number);
            String name = "Печь П-" + number;
            importedOvenNames.add(name);
            String status = row[2].strip().toLowerCase(Locale.ROOT);
            if (!List.of("active", "repair", "out_of_series").contains(status))
                throw new IllegalArgumentException("неизвестный статус печи: " + status);

            Equipment directoryEntry = equipmentByName.get(name);
            if (directoryEntry == null) {
                directoryEntry = new Equipment();
                directoryEntry.setName(name);
                directoryEntry.setType(EquipmentType.OVEN);
                equipmentToSave.add(directoryEntry);
            }
            directoryEntry.setAvailable(!"repair".equals(status) && !"out_of_series".equals(status));

            CokeOvens oven = ovenByName.get(name);
            if (oven == null) {
                oven = new CokeOvens();
                oven.setNameOven(name);
            }
            oven.setBatteryNumber(1);
            oven.setState("repair".equals(status) ? org.example.coke_factory.entity.OvenState.REPAIR
                    : org.example.coke_factory.entity.OvenState.COKING);
            fresh.add(oven);
            maxNumber = Math.max(maxNumber, number);
            if ("repair".equals(status) || "out_of_series".equals(status)) unavailable.add(number);
        }
        if (maxNumber < 2)
            throw new IllegalArgumentException("в батарее должно быть не менее двух печей");
        // Номера, отсутствующие в файле, считаются выведенными из серии.
        for (int number = 1; number <= maxNumber; number++) {
            if (!seenNumbers.contains(number)) unavailable.add(number);
        }

        // Старые печи, отсутствующие в импортируемом составе, сохраняем для истории,
        // но делаем недоступными, чтобы не нарушать внешние ссылки.
        for (CokeOvens old : existingOvens) {
            if (old.getNameOven() == null || !old.getNameOven().matches("Печь П-\\d+")) continue;
            int oldNumber = Integer.parseInt(old.getNameOven().substring("Печь П-".length()));
            if (!seenNumbers.contains(oldNumber)) {
                old.setState(org.example.coke_factory.entity.OvenState.REPAIR);
                Equipment linked = old.getEquipment();
                if (linked != null) linked.setAvailable(false);
            }
        }
        // Старое оборудование, отсутствующее в новом файле, тоже помечаем недоступным.
        existingEquipment.stream()
                .filter(e -> !importedOvenNames.contains(e.getName()))
                .forEach(e -> e.setAvailable(false));
        // Существующие записи оборудования сохраняем, в том числе не попавшие в новый CSV.
        equipmentToSave.addAll(existingEquipment);
        List<Equipment> savedEquipment = equipment.saveAll(equipmentToSave);
        java.util.Map<String, Equipment> byName = new java.util.HashMap<>();
        savedEquipment.forEach(e -> byName.put(e.getName(), e));
        // Также включаем уже сохранённые записи, которые могли не попасть в saveAll.
        existingEquipment.forEach(e -> byName.put(e.getName(), e));
        fresh.forEach(oven -> {
            Equipment linked = byName.get(oven.getNameOven());
            if (linked != null) oven.setEquipment(linked);
        });
        List<CokeOvens> ovensToSave = new ArrayList<>(existingOvens);
        for (CokeOvens oven : fresh) {
            if (!ovensToSave.contains(oven)) ovensToSave.add(oven);
        }
        ovens.saveAll(ovensToSave);

        // симулятор пересобирается под новый состав батареи
        simulator.rebuildBattery(maxNumber, unavailable);
        audit.log("IMPORT_OVENS_CSV", "CokeOvens", null,
                "Импортирован справочник печей: %d шт, выведены %s"
                        .formatted(fresh.size(), unavailable.isEmpty() ? "нет" : unavailable));
        return new ImportResult("ovens", fresh.size(), unavailable.size(),
                "Состав батареи обновлён: %d печей, из них недоступны %d"
                        .formatted(fresh.size(), unavailable.size()));
    }

    /** Обновить справочник машин из machines.csv (docs/04 §3): battery_code;code;machine_type;status;… */
    @Transactional
    public ImportResult importMachines(String csv) {
        List<String[]> rows = parse(csv);
        if (rows.isEmpty())
            throw new IllegalArgumentException("файл машин пуст");
        List<Equipment> fresh = new ArrayList<>();
        List<MachineKind> down = new ArrayList<>();
        java.util.Set<EquipmentType> seenTypes = new java.util.HashSet<>();
        String batteryCode = null;
        for (String[] row : rows) {
            if (row.length < 4)
                throw new IllegalArgumentException(
                        "в строке машины меньше четырёх колонок: " + String.join(";", row));
            if (batteryCode == null) batteryCode = row[0].strip();
            else if (!batteryCode.equals(row[0].strip()))
                throw new IllegalArgumentException("один CSV-файл должен содержать машины одной батареи");
            EquipmentType type = machineType(row[2].strip().toLowerCase(Locale.ROOT));
            if (!seenTypes.add(type))
                throw new IllegalArgumentException("в модели поддерживается одна машина каждого типа: " + type);
            String status = row[3].strip().toLowerCase(Locale.ROOT);
            if (!List.of("running", "idle", "failed").contains(status))
                throw new IllegalArgumentException("неизвестный статус машины: " + status);
            Equipment machine = new Equipment();
            machine.setName(row[1].strip());
            machine.setType(type);
            machine.setAvailable(!"failed".equals(status));
            fresh.add(machine);
            MachineKind kind = machineKind(type);
            if (kind != null && !machine.getAvailable()) down.add(kind);
        }

        if (!seenTypes.containsAll(List.of(EquipmentType.PUSHER, EquipmentType.DOOR_MACHINE,
                EquipmentType.LOADING_CAR, EquipmentType.QUENCHING_CAR))) {
            throw new IllegalArgumentException("CSV должен содержать по одной машине каждого типа: pusher, door_machine, charging_wagon, quench_wagon");
        }

        // Не удаляем старые machine-строки: на них могут ссылаться notifications.
        // При одной машине каждого типа обновляем существующую запись по типу.
        List<Equipment> existingMachines = equipment.findByTypeIn(List.of(
                EquipmentType.PUSHER, EquipmentType.QUENCHING_CAR,
                EquipmentType.DOOR_MACHINE, EquipmentType.LOADING_CAR));
        java.util.Map<EquipmentType, Equipment> existingByType = new java.util.HashMap<>();
        existingMachines.forEach(e -> existingByType.putIfAbsent(e.getType(), e));
        for (Equipment imported : fresh) {
            Equipment existing = existingByType.get(imported.getType());
            if (existing != null) {
                imported.setId(existing.getId());
            }
        }
        equipment.saveAll(fresh);

        // Симулятор содержит по одной машине каждого типа; применяем последнее
        // импортированное состояние типа, включая возврат ранее отключённой машины.
        for (MachineKind kind : MachineKind.values()) {
            boolean isDown = down.contains(kind);
            if (isDown) simulator.setMachineDown(kind, 0);
            else simulator.setMachineUp(kind);
        }
        audit.log("IMPORT_MACHINES_CSV", "Equipment", null,
                "Импортирован справочник машин: %d шт, в простое %d".formatted(fresh.size(), down.size()));
        return new ImportResult("machines", fresh.size(), down.size(),
                "Справочник машин заменён: %d записей, в простое %d".formatted(fresh.size(), down.size()));
    }

    public record ImportResult(String kind, int imported, int unavailable, String summary) { }

    // ------------------------------------------------------------------
    // разбор CSV
    // ------------------------------------------------------------------

    /** Строки файла без заголовка и пустых строк, разбитые по «;». */
    private static List<String[]> parse(String csv) {
        if (csv == null || csv.isBlank())
            throw new IllegalArgumentException("файл не передан");
        List<String[]> rows = new ArrayList<>();
        String[] lines = csv.replace("\r\n", "\n").split("\n");
        boolean firstDataLine = true;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (firstDataLine && line.toLowerCase(Locale.ROOT).contains("battery_code")) {
                firstDataLine = false;
                continue;
            }
            firstDataLine = false;
            rows.add(line.split(";", -1));
        }
        return rows;
    }

    /** machine_type из docs/04 §3 → тип справочника оборудования. */
    private static EquipmentType machineType(String csvType) {
        return switch (csvType) {
            case "pusher" -> EquipmentType.PUSHER;
            case "quench_wagon", "quenching_car" -> EquipmentType.QUENCHING_CAR;
            case "door_machine" -> EquipmentType.DOOR_MACHINE;
            case "charging_wagon", "loading_car" -> EquipmentType.LOADING_CAR;
            default -> throw new IllegalArgumentException(
                    "неизвестный тип машины %s, ожидается pusher | door_machine | charging_wagon | quench_wagon"
                            .formatted(csvType));
        };
    }

    private static MachineKind machineKind(EquipmentType type) {
        return switch (type) {
            case PUSHER -> MachineKind.PUSHING;
            case QUENCHING_CAR -> MachineKind.QUENCH_CAR;
            case DOOR_MACHINE -> MachineKind.DOOR_MACHINE;
            case LOADING_CAR -> MachineKind.LOADING_CAR;
            default -> null;
        };
    }
}
