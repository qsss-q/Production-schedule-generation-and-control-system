package org.example.coke_factory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.coke_factory.entity.CokeOvens;
import org.example.coke_factory.entity.OperationStatus;
import org.example.coke_factory.entity.RunStatus;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.entity.ScheduleRun;
import org.example.coke_factory.entity.ScheduleSettings;
import org.example.coke_factory.entity.Notification;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.repository.ScheduleOperationRepository;
import org.example.coke_factory.repository.ScheduleRunRepository;
import org.example.coke_factory.repository.ScheduleSettingsRepository;
import org.example.coke_factory.repository.NotificationRepository;
import org.example.coke_factory.service.ScheduleRunService.CompareRequest;
import org.example.coke_factory.service.ScheduleRunService.CompareResult;
import org.example.coke_factory.service.ScheduleRunService.CreateRunRequest;
import org.example.coke_factory.service.ScheduleRunService.ManualEditRequest;
import org.example.coke_factory.service.ScheduleRunService.RunDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Прогоны планировщика без Spring и БД: живой симулятор, репозитории — карта
 * в памяти. Проверяются: план уходит в симулятор, статусы по факту, ручная
 * правка с причиной, пересчёт по возмущению, сравнение алгоритмов на одном входе.
 */
class ScheduleRunServiceTest {

    private SimulatorService simulator;
    private ScheduleRunService service;
    private AuditService audit;
    private ScheduleSettingsRepository settingsRepository;
    private NotificationRepository notificationRepository;
    private final Map<String, ScheduleRun> runStore = new HashMap<>();
    private final List<String> runOrder = new ArrayList<>();
    private final List<ScheduleOperation> opStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        simulator = new SimulatorService();
        audit = mock(AuditService.class);

        ScheduleRunRepository runRepo = mock(ScheduleRunRepository.class);
        when(runRepo.save(any())).thenAnswer(inv -> {
            ScheduleRun run = inv.getArgument(0);
            runStore.put(run.getId(), run);
            runOrder.add(run.getId());
            return run;
        });
        when(runRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(runStore.get(inv.getArgument(0, String.class))));
        when(runRepo.findTopByStatusOrderByCreatedAtDesc(any()))
                .thenAnswer(inv -> {
                    RunStatus status = inv.getArgument(0);
                    return runOrder.stream().map(runStore::get)
                            .filter(r -> r.getStatus() == status)
                            .reduce((a, b) -> b);           // последний по вставке
                });
        when(runRepo.findAllByOrderByCreatedAtDesc()).thenAnswer(inv ->
                runOrder.stream().map(runStore::get).toList().reversed());

        ScheduleOperationRepository opRepo = mock(ScheduleOperationRepository.class);
        when(opRepo.save(any())).thenAnswer(inv -> {
            ScheduleOperation op = inv.getArgument(0);
            if (op.getId() == null) {
                op.setId((long) (opStore.size() + 1));
                opStore.add(op);
            } else {
                opStore.replaceAll(stored -> stored.getId().equals(op.getId()) ? op : stored);
            }
            return op;
        });
        when(opRepo.saveAll(any())).thenAnswer(inv -> {
            Iterable<ScheduleOperation> ops = inv.getArgument(0);
            List<ScheduleOperation> saved = new ArrayList<>();
            ops.forEach(op -> {
                if (op.getId() == null) op.setId((long) (opStore.size() + 1));
                int existingIndex = -1;
                for (int i = 0; i < opStore.size(); i++) {
                    if (opStore.get(i).getId().equals(op.getId())) {
                        existingIndex = i;
                        break;
                    }
                }
                if (existingIndex >= 0) opStore.set(existingIndex, op);
                else opStore.add(op);
                saved.add(op);
            });
            return saved;
        });
        when(opRepo.findByRunIdOrderByIdAsc(anyString())).thenAnswer(inv ->
                opStore.stream()
                        .filter(op -> inv.getArgument(0, String.class).equals(op.getRunId()))
                        .sorted(Comparator.comparing(ScheduleOperation::getId))
                        .toList());
        when(opRepo.findById(any(Long.class))).thenAnswer(inv ->
                opStore.stream()
                        .filter(op -> op.getId().equals(inv.getArgument(0, Long.class)))
                        .findFirst());

        CokeOvenRepository ovenRepo = mock(CokeOvenRepository.class);
        when(ovenRepo.findByNameOven(anyString())).thenAnswer(inv -> {
            CokeOvens oven = new CokeOvens();
            oven.setId(1L);
            oven.setNameOven(inv.getArgument(0, String.class));
            return Optional.of(oven);
        });

        settingsRepository = mock(ScheduleSettingsRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        service = new ScheduleRunService(simulator, runRepo, opRepo, ovenRepo, audit,
                new ObjectMapper(), settingsRepository, notificationRepository);
    }

    @Test
    void settingsApplyShiftWindowAndDefaultPriority() {
        ScheduleSettings active = new ScheduleSettings();
        active.setActive(true);
        active.setStepMinutes(15);
        active.setShiftStart(LocalTime.of(8, 0));
        active.setShiftEnd(LocalTime.of(20, 0));
        active.setMaxIdleMinutes(30);
        active.setDefaultPriority(3);
        when(settingsRepository.findFirstByActiveTrue()).thenReturn(Optional.of(active));

        RunDto run = service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null),
                "тест смены и приоритета");

        List<ScheduleOperation> generated = opStore.stream()
                .filter(op -> run.id().equals(op.getRunId()))
                .toList();
        assertThat(generated).isNotEmpty().allMatch(op -> op.getPriority() == 3);
        assertThat(generated).allSatisfy(op -> {
            assertThat(op.getPlannedStart().toLocalTime()).isBetween(
                    LocalTime.of(8, 0), LocalTime.of(19, 30));
            assertThat(op.getPlannedEnd().toLocalTime()).isBeforeOrEqualTo(LocalTime.of(20, 0));
        });
    }

    @Test
    void createRun_appliesPlanToSimulator_andCreatesOperations() {
        RunDto run = service.createRun(new CreateRunRequest("b2_idle", null, null, null, null, null),
                "тест прогона");

        assertThat(run.algorithm()).isEqualTo("b2_idle");
        assertThat(run.status()).isEqualTo("CALCULATED");
        assertThat(run.metrics()).containsKeys("lateCount", "violations", "calcUniformity");
        assertThat(run.totalOperations()).isGreaterThan(50);
        assertThat(opStore).isNotEmpty();
        assertThat(opStore).allMatch(op -> run.id().equals(op.getRunId()));
        assertThat(opStore).allMatch(op -> op.getOvenNumber() != null);
        // живой прогон идёт по плану: фактические выдачи совпадают с плановым временем
        simulator.control(new SimulatorService.ControlRequest("step", 180L));
        var history = simulator.dischargeHistory();
        assertThat(history).isNotEmpty();
        for (var record : history)
            assertThat(record.actualMin() - record.plannedMin()).isLessThanOrEqualTo(1.0);
        verify(audit, atLeastOnce()).log(contains("SCHEDULE_RUN"), any(), any(), any());
    }

    @Test
    void simulatorReset_archivesStaleRun_insteadOfTrackingOtherTimeline() {
        RunDto run = service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null), "тест");
        simulator.control(new SimulatorService.ControlRequest("step", 60L));
        service.syncTick();
        assertThat(runStore.get(run.id()).getStatus()).isEqualTo(RunStatus.CALCULATED);

        // сброс (так же ведут себя перезапуск приложения и загрузка снимка): новая временная линия
        simulator.control(new SimulatorService.ControlRequest("reset", null));
        service.syncTick();

        assertThat(runStore.get(run.id()).getStatus()).isEqualTo(RunStatus.ARCHIVED);
        assertThat(service.activeRun()).isEmpty();
        assertThat(opStore).noneMatch(op -> op.getStatus() == OperationStatus.OVERDUE);
        verify(audit).log(contains("SCHEDULE_RUN_ARCHIVED"), any(), any(), any());

        // новый график формируется от текущего состояния и снова отслеживается
        RunDto fresh = service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null), "после сброса");
        service.syncTick();
        assertThat(runStore.get(fresh.id()).getStatus()).isEqualTo(RunStatus.CALCULATED);
    }

    @Test
    void statuses_followSimulatorFact() {
        service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null), "тест");

        simulator.control(new SimulatorService.ControlRequest("step", 60L));
        service.syncTick();                                     // ревизий нет — только статусы
        var ops = opStore.stream().filter(op -> !Boolean.TRUE.equals(op.getManual())).toList();
        assertThat(ops).anySatisfy(op ->
                assertThat(op.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS));

        // до 3-го часа: несколько выдач выполнены
        simulator.control(new SimulatorService.ControlRequest("step", 150L));
        service.syncTick();
        assertThat(ops).anySatisfy(op ->
                assertThat(op.getStatus()).isEqualTo(OperationStatus.DONE));
    }

    @Test
    void excludedOven_marksOnlyItsOperationImpossible_withoutRecalculatingRun() {
        RunDto created = service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null), "тест");
        int before = runStore.size();
        List<ScheduleOperation> original = opStore.stream()
                .filter(op -> created.id().equals(op.getRunId())).toList();
        Map<Long, LocalDateTime> originalTimes = original.stream().collect(
                java.util.stream.Collectors.toMap(ScheduleOperation::getId, ScheduleOperation::getPlannedStart));

        simulator.setOvenAvailable(7, false, "ремонт кладки");
        service.syncTick();

        assertThat(runStore).hasSize(before);
        assertThat(runStore.get(created.id()).getStatus()).isEqualTo(RunStatus.CALCULATED);
        assertThat(opStore.stream().filter(op -> created.id().equals(op.getRunId())))
                .allSatisfy(op -> assertThat(op.getPlannedStart()).isEqualTo(originalTimes.get(op.getId())));
        var excluded = opStore.stream()
                .filter(op -> created.id().equals(op.getRunId()))
                .filter(op -> Integer.valueOf(7).equals(op.getOvenNumber())).toList();
        assertThat(excluded).anySatisfy(op ->
                assertThat(op.getStatus()).isEqualTo(OperationStatus.IMPOSSIBLE));
        verify(notificationRepository, atLeastOnce()).save(any(Notification.class));

        simulator.setOvenAvailable(7, true, "ремонт завершён");
        service.syncTick();
        assertThat(excluded).allSatisfy(op -> assertThat(op.getStatus())
                .isNotEqualTo(OperationStatus.IMPOSSIBLE));
        assertThat(runStore).hasSize(before);
    }

    @Test
    void manualEdit_requiresReason_andMovesDischarge() {
        service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null), "тест");
        ScheduleOperation op = opStore.get(opStore.size() - 1);
        assertThat(op.getStatus()).isEqualTo(OperationStatus.PLANNED);
        Map<Long, LocalDateTime> beforeTimes = opStore.stream()
                .filter(o -> op.getRunId().equals(o.getRunId()))
                .collect(java.util.stream.Collectors.toMap(ScheduleOperation::getId, ScheduleOperation::getPlannedStart));

        assertThatThrownBy(() -> service.manualEdit(op.getId(), new ManualEditRequest(null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("причина");

        simulator.control(new SimulatorService.ControlRequest("step", 10L));
        var newTime = op.getPlannedStart().plusMinutes(120);
        ScheduleOperation edited = service.manualEdit(op.getId(), new ManualEditRequest(
                newTime.atZone(SimulatorService.PLANT_ZONE).toInstant().toString(),
                "перенос по согласованию с мастером", 3));

        assertThat(edited.getManual()).isTrue();
        assertThat(edited.getNote()).contains("мастером");
        assertThat(edited.getPlannedStart()).isEqualTo(newTime);
        assertThat(edited.getPriority()).isEqualTo(3);
        // Все остальные операции сохраняют свои плановые времена.
        assertThat(opStore.stream().filter(o -> beforeTimes.containsKey(o.getId()) && !o.getId().equals(op.getId())))
                .allSatisfy(o -> assertThat(o.getPlannedStart()).isEqualTo(beforeTimes.get(o.getId())));
        verify(audit, atLeastOnce()).log(contains("MANUAL_SCHEDULE_EDIT"), any(), any(), any());
    }

    @Test
    void manualEdit_carriesOverToNextRun_andPlanWaitsForIt() {
        service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null), "тест");
        simulator.control(new SimulatorService.ControlRequest("step", 10L));
        ScheduleOperation op = opStore.get(5);
        var newTime = SimulatorService.toPlantTime(simulator.simMin() + 150);
        service.manualEdit(op.getId(), new ManualEditRequest(
                newTime.atZone(SimulatorService.PLANT_ZONE).toInstant().toString(), "ручной перенос"));

        int opsBefore = opStore.size();
        service.syncTick();                                     // ревизия от правки симулятора? нет — правка меняет план без ревизии
        // форсируем пересчёт явным прогоном того же алгоритма
        RunDto second = service.createRun(new CreateRunRequest("b1_fifo", null, null, null, null, null),
                "пересчёт после правки");

        assertThat(second.parentId()).isNotNull();
        assertThat(opStore.size()).isGreaterThan(opsBefore);
        // ручная правка переехала в новый прогон со своим временем
        var carried = opStore.stream()
                .filter(o -> second.id().equals(o.getRunId()) && Boolean.TRUE.equals(o.getManual()))
                .toList();
        assertThat(carried).hasSize(1);
        assertThat(carried.get(0).getOvenNumber()).isEqualTo(op.getOvenNumber());
        assertThat(carried.get(0).getPlannedStart()).isEqualTo(newTime);
        assertThat(opStore.stream()
                .filter(o -> second.id().equals(o.getRunId()))
                .filter(o -> !Boolean.TRUE.equals(o.getManual()))
                .filter(o -> op.getOvenNumber().equals(o.getOvenNumber()))
                .toList()).isEmpty();
        // печь в плане нового прогона не раньше ручного времени
        int idx = -1;
        var slice = simulator.planInput(Integer.MAX_VALUE);
        for (int k = 0; k < slice.horizon(); k++)
            if (slice.ovens()[k] == op.getOvenNumber()) idx = k;
        double[] plan = simulator.currentPlan();
        assertThat(idx).isGreaterThan(0);
        assertThat(plan[Math.min(idx, plan.length - 1)])
                .isGreaterThanOrEqualTo(SimulatorService.simMinutesOf(newTime) - 1e-9);
    }

    @Test
    void compare_runsAllAlgorithmsOnSameInput() {
        simulator.control(new SimulatorService.ControlRequest("step", 60L));
        simulator.inject(new SimulatorService.FailureRequest("pushJam", null));
        simulator.control(new SimulatorService.ControlRequest("step", 120L));

        CompareResult result = service.compare(new CompareRequest(
                List.of("b1_fifo", "b2_idle", "b3_smooth"), null, 1260.0));

        assertThat(result.runs()).hasSize(3);
        assertThat(result.criteria()).isNotEmpty();
        assertThat(result.inputNote()).contains("реплей");
        long b1Late = longValue(result.runs().get(0).values().get("late_operations"));
        long b2Late = longValue(result.runs().get(1).values().get("late_operations"));
        long b3Late = longValue(result.runs().get(2).values().get("late_operations"));
        assertThat(b2Late).isLessThanOrEqualTo(b1Late);
        assertThat(b3Late).isLessThanOrEqualTo(b1Late);
        // серийность во всех строках — текущая
        assertThat(result.runs()).allSatisfy(r ->
                assertThat(r.seriality()).isEqualTo(simulator.seriality()));
    }

    @Test
    void compare_otherSeriality_rebuildsStationaryBattery() {
        CompareResult result = service.compare(new CompareRequest(
                List.of("b1_fifo", "b3_smooth"), "5-2", 1260.0));
        assertThat(result.runs()).hasSize(2);
        assertThat(result.runs()).allSatisfy(r -> assertThat(r.seriality()).isEqualTo("5-2"));
        assertThat(result.inputNote()).contains("стационаре");
    }

    @Test
    void createRun_rejectsForeignSerialityAndSeed() {
        assertThatThrownBy(() -> service.createRun(
                new CreateRunRequest("b1_fifo", "5-2", null, null, null, null), "тест"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("серийности");
        long currentSeed = simulator.state().seed();
        assertThatThrownBy(() -> service.createRun(
                new CreateRunRequest("b1_fifo", null, currentSeed + 1, null, null, null), "тест"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }
}
