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
 * Формат: строки с «;», первая строка — заголовок. Печи заменяют состав
 * батареи (симулятор пересобирается под новое число печей), машины —
 * справочник оборудования; статус repair/failed трактуется как недоступность.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectoryCsvService {

    private final CokeOvenRepository ovens;
    private final EquipmentRepository equipment;
    private final SimulatorService simulator;
    private final AuditService audit;

    /** Заменить справочник печей из ovens.csv (docs/04 §2): battery_code;number;status;… */
    @Transactional
    public ImportResult importOvens(String csv) {
        List<String[]> rows = parse(csv);
        if (rows.isEmpty())
            throw new IllegalArgumentException("файл печей пуст");
        List<CokeOvens> fresh = new ArrayList<>();
        int maxNumber = 0;
        List<Integer> unavailable = new ArrayList<>();
        for (String[] row : rows) {
            // battery_code;number;status;… — остальные колонки справочные
            if (row.length < 3)
                throw new IllegalArgumentException(
                        "в строке печи меньше трёх колонок: " + String.join(";", row));
            int number = Integer.parseInt(row[1].strip());
            if (number < 1) throw new IllegalArgumentException("номер печи должен быть ≥ 1: " + number);
            String status = row[2].strip().toLowerCase(Locale.ROOT);
            CokeOvens oven = new CokeOvens();
            oven.setNameOven("Печь П-" + number);
            oven.setBatteryNumber(1);
            fresh.add(oven);
            maxNumber = Math.max(maxNumber, number);
            if ("repair".equals(status) || "out_of_series".equals(status)) unavailable.add(number);
        }

        ovens.deleteAll();
        ovens.saveAll(fresh);

        // симулятор пересобирается под новый состав батареи
        simulator.rebuildBattery(maxNumber, unavailable);
        audit.log("IMPORT_OVENS_CSV", "CokeOvens", null,
                "Импортирован справочник печей: %d шт, выведены %s"
                        .formatted(fresh.size(), unavailable.isEmpty() ? "нет" : unavailable));
        return new ImportResult("ovens", fresh.size(), unavailable.size(),
                "Состав батареи заменён: %d печей, из них в ремонте %d"
                        .formatted(fresh.size(), unavailable.size()));
    }

    /** Заменить справочник машин из machines.csv (docs/04 §3): battery_code;code;machine_type;status;… */
    @Transactional
    public ImportResult importMachines(String csv) {
        List<String[]> rows = parse(csv);
        if (rows.isEmpty())
            throw new IllegalArgumentException("файл машин пуст");
        List<Equipment> fresh = new ArrayList<>();
        List<MachineKind> down = new ArrayList<>();
        for (String[] row : rows) {
            if (row.length < 4)
                throw new IllegalArgumentException(
                        "в строке машины меньше четырёх колонок: " + String.join(";", row));
            EquipmentType type = machineType(row[2].strip().toLowerCase(Locale.ROOT));
            Equipment machine = new Equipment();
            machine.setName(row[1].strip());
            machine.setType(type);
            machine.setAvailable(!"failed".equals(row[3].strip().toLowerCase(Locale.ROOT)));
            fresh.add(machine);
            MachineKind kind = machineKind(type);
            if (kind != null && !machine.getAvailable()) down.add(kind);
        }

        // печи в справочнике оборудования не трогаем: они живут в CokeOvens
        equipment.findByTypeIn(List.of(EquipmentType.PUSHER, EquipmentType.QUENCHING_CAR))
                .forEach(equipment::delete);
        equipment.saveAll(fresh);

        for (MachineKind kind : down) simulator.setMachineDown(kind, 0);
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
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (i == 0 && line.toLowerCase(Locale.ROOT).contains("battery_code")) continue; // заголовок
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
