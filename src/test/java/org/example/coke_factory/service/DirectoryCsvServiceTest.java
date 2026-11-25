package org.example.coke_factory.service;

import org.example.coke_factory.entity.Equipment;
import org.example.coke_factory.entity.EquipmentType;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.repository.EquipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import ru.evraz.koks.sim.MachineKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Импорт справочников из CSV (docs/04 §1–3): состав батареи пересобирается, машины — в простой. */
class DirectoryCsvServiceTest {

    private SimulatorService simulator;
    private DirectoryCsvService service;
    private AuditService audit;
    private final List<org.example.coke_factory.entity.CokeOvens> ovenStore = new ArrayList<>();
    private final List<Equipment> equipmentStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        simulator = new SimulatorService();
        audit = mock(AuditService.class);

        CokeOvenRepository ovenRepo = mock(CokeOvenRepository.class);
        when(ovenRepo.saveAll(any())).thenAnswer(inv -> {
            Iterable<org.example.coke_factory.entity.CokeOvens> ovens = inv.getArgument(0);
            ovens.forEach(ovenStore::add);
            return new ArrayList<>();
        });
        when(ovenRepo.findByNameOven(anyString())).thenAnswer(inv -> ovenStore.stream()
                .filter(o -> o.getNameOven().equals(inv.getArgument(0, String.class)))
                .findFirst());

        EquipmentRepository equipmentRepo = mock(EquipmentRepository.class);
        when(equipmentRepo.saveAll(any())).thenAnswer(inv -> {
            Iterable<Equipment> machines = inv.getArgument(0);
            machines.forEach(equipmentStore::add);
            return new ArrayList<>();
        });
        when(equipmentRepo.findByTypeIn(any())).thenAnswer(inv -> equipmentStore.stream()
                .filter(e -> ((List<EquipmentType>) inv.getArgument(0)).contains(e.getType()))
                .toList());

        service = new DirectoryCsvService(ovenRepo, equipmentRepo, simulator, audit);
    }

    @Test
    void ovensCsv_rebuildsBatteryWithRepairStatuses() {
        int before = simulator.ovenCount();

        var result = service.importOvens("""
                battery_code;number;status;chamber_volume_m3
                Б-1;1;active;30.1
                Б-1;2;active;30.1
                Б-1;3;repair;30.1
                """);

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.unavailable()).isEqualTo(1);
        assertThat(simulator.ovenCount()).isEqualTo(3).isNotEqualTo(before);
        assertThat(simulator.ovenAvailable(3)).isFalse();
        assertThat(simulator.ovenAvailable(1)).isTrue();
        // печь в ремонте пропускается: прогноз её не показывает
        assertThat(simulator.ovens()).anySatisfy(o -> {
            assertThat(o.number()).isEqualTo(3);
            assertThat(o.state()).isEqualTo("unavailable");
        });
    }

    @Test
    void machinesCsv_failedRowPutsMachineDown() {
        var result = service.importMachines("""
                battery_code;code;machine_type;status;block
                Б-1;КВ-1;pusher;running;Б-1
                Б-1;ТВ-1;quench_wagon;failed;Б-1
                """);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.unavailable()).isEqualTo(1);
        assertThat(simulator.machineDown(MachineKind.QUENCH_CAR)).isTrue();
        assertThat(simulator.machineDown(MachineKind.PUSHING)).isFalse();
    }

    @Test
    void machinesUp_availabilityEndpointReturnsMachine() {
        simulator.setMachineDown(MachineKind.PUSHING, 0);
        assertThat(simulator.machineDown(MachineKind.PUSHING)).isTrue();
        simulator.setMachineUp(MachineKind.PUSHING);
        assertThat(simulator.machineDown(MachineKind.PUSHING)).isFalse();
    }

    @Test
    void brokenCsv_isRejected() {
        assertThatThrownBy(() -> service.importOvens("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.importOvens("""
                battery_code;number;status
                Б-1;x;active
                """))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> service.importMachines("""
                battery_code;code;machine_type;status
                Б-1;КВ-1;helicopter;running
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("helicopter");
    }
}
