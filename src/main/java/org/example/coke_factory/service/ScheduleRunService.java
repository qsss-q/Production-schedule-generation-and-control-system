package org.example.coke_factory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.coke_factory.entity.OperationStatus;
import org.example.coke_factory.entity.RunStatus;
import org.example.coke_factory.entity.ScheduleOperation;
import org.example.coke_factory.entity.ScheduleRun;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.repository.ScheduleOperationRepository;
import org.example.coke_factory.repository.ScheduleRunRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.evraz.koks.sim.Simulator;
import ru.evraz.koks.sim.planner.Metrics;
import ru.evraz.koks.sim.planner.ScheduleAlgorithm;
import ru.evraz.koks.sim.planner.ScheduleAlgorithms;
import ru.evraz.koks.sim.planner.ScheduleInput;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/**
 * Прогоны планировщика (docs/10 §6, docs/09 §8): формирование графика выбранным
 * алгоритмом, версии с пересчётом по возмущениям, живые статусы операций,
 * ручная правка и сравнение алгоритмов на одном входе.
 *
 * <p>Сравнение честное: вход ({@link ScheduleInput}) — один срез живого прогона,
 * офлайн-реплей каждой альтернативы — на копии батареи из одного снимка
 * ({@link Simulator#restore}), тем же seed. Живой прогон при этом идёт по плану
 * выбранного алгоритма.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleRunService {

    /** Длительность операции выдачи в графике, мин. */
    private static final int OPERATION_MINUTES = 30;

    /** Допуск опоздания, после которого операция просрочена (docs/05 §6, M1). */
    private static final double LATE_TOLERANCE_MIN = 5.0;

    /** Окно сравнения по умолчанию: два оборота — чтобы ОЕЭ печей была измерима. */
    private static final double DEFAULT_COMPARE_WINDOW_MIN = 2 * 840.0;

    private final SimulatorService simulator;
    private final ScheduleRunRepository runs;
    private final ScheduleOperationRepository operations;
    private final CokeOvenRepository ovens;
    private final AuditService audit;
    private final ObjectMapper mapper;

    /** Ревизия на момент последнего пересчёта — чтобы ловить новые возмущения. */
    private long lastRevision;

    // ------------------------------------------------------------------
    // запросы
    // ------------------------------------------------------------------

    public record CreateRunRequest(String algorithm, String seriality, Long seed,
                                   Integer horizon, Integer holdNotifyMin,
                                   Integer holdAutoSkipMin) { }

    public record ManualEditRequest(String plannedStart, String reason) { }

    public record CompareRequest(List<String> algorithmCodes, String seriality,
                                 Double windowMin) { }

    // ------------------------------------------------------------------
    // формирование и пересчёт прогона
    // ------------------------------------------------------------------

    /**
     * Посчитать график выбранным алгоритмом от текущего состояния батареи.
     * План устанавливается в живой прогон (выдача пойдёт по нему), офлайн-реплей
     * на копии батареи даёт метрики того же входа. Прошлые прогоны остаются
     * в истории со своими операциями.
     */
    @Transactional
    public RunDto createRun(CreateRunRequest request, String reason) {
        ScheduleAlgorithm algo = ScheduleAlgorithms.byCode(
                request.algorithm() == null ? "b1_fifo" : request.algorithm());
        if (request.seriality() != null
                && !request.seriality().strip().equals(simulator.seriality()))
            throw new IllegalArgumentException(
                    "схема серийности %s не совпадает с текущей конфигурацией батареи %s"
                            .formatted(request.seriality(), simulator.seriality()));
        if (request.seed() != null && request.seed() != simulator.state().seed())
            throw new IllegalArgumentException(
                    "seed %d не совпадает с текущим прогоном %d: сравнение возможно только на том же входе"
                            .formatted(request.seed(), simulator.state().seed()));

        ScheduleInput input = simulator.planInput(
                request.horizon() == null ? Integer.MAX_VALUE : request.horizon());
        ScheduleRun previous = runs.findTopByStatusOrderByCreatedAtDesc(RunStatus.CALCULATED)
                .orElse(null);
        List<ScheduleOperation> manualOps = previous == null ? List.of()
                : operations.findByRunIdOrderByIdAsc(previous.getId()).stream()
                        .filter(op -> Boolean.TRUE.equals(op.getManual()))
                        .toList();
        double[] plan = applyManualCarryOver(algo.build(input), input, manualOps);

        // офлайн-реплей: копия батареи из того же снимка, тот же seed
        Simulator.BatteryState snapshot = simulator.snapshotState();
        long from = snapshot.simMin();
        long to = Math.max(from + 1, (long) Math.ceil(plan[plan.length - 1]));
        Metrics.Result metrics = replay(snapshot, algo, from, to);

        simulator.applyPlan(plan);

        ScheduleRun run = new ScheduleRun();
        run.setAlgorithm(algo.code());
        run.setSeriality(simulator.seriality());
        run.setSeed(simulator.state().seed());
        run.setReason(reason == null || reason.isBlank() ? "формирование графика" : reason);
        run.setStatus(RunStatus.CALCULATED);
        run.setInputHash(inputHash(input, algo));
        run.setMetricsJson(toJson(metricsJson(metrics, copy, snapshot)));
        run.setWindowFromMin(from);
        run.setWindowToMin(to);
        if (request.holdNotifyMin() != null) run.setHoldNotifyMin(request.holdNotifyMin());
        if (request.holdAutoSkipMin() != null) run.setHoldAutoSkipMin(request.holdAutoSkipMin());
        if (previous != null) {
            run.setParentId(previous.getId());
            previous.setStatus(RunStatus.ARCHIVED);
            runs.save(previous);
        }
        run = runs.save(run);

        int created = createOperations(run, plan, input.ovens(), manualOps);
        lastRevision = simulator.revision();

        audit.log("SCHEDULE_RUN", "ScheduleRun", null,
                "Прогон %s: алгоритм %s, схема %s, операций %d. %s"
                        .formatted(shortId(run.getId()), algo.title(), run.getSeriality(),
                                created, run.getReason()));
        return toDto(run, created);
    }

    /**
     * Пересчёт действующего прогона: тот же алгоритм и параметры, но от нового
     * состояния (после отказа, простоя машины, вывода печи, ручной правки).
     * Новая версия с parentId, старая остаётся в истории.
     */
    @Transactional
    public RunDto recalc(ScheduleRun previous, String reason) {
        return createRun(new CreateRunRequest(previous.getAlgorithm(), previous.getSeriality(),
                previous.getSeed(), null, previous.getHoldNotifyMin(), previous.getHoldAutoSkipMin()),
                reason + " (ревизия " + simulator.revision() + ")");
    }

    /**
     * Тик синхронизации: раз в секунду проверяет возмущения и двигает статусы
     * операций действующего прогона по факту симулятора.
     */
    @Scheduled(fixedRate = 1000)
    public void syncTick() {
        try {
            ScheduleRun active = runs.findTopByStatusOrderByCreatedAtDesc(RunStatus.CALCULATED)
                    .orElse(null);
            if (active == null) return;

            long revision = simulator.revision();
            if (revision != lastRevision) {
                lastRevision = revision;
                ScheduleRun fresh = runs.findTopByStatusOrderByCreatedAtDesc(RunStatus.CALCULATED)
                        .orElse(active);
                RunDto recalculated = recalc(fresh, "пересчёт графика: изменились условия работы батареи");
                syncStatuses(runs.findById(recalculated.id()).orElse(fresh));
                return;
            }
            syncStatuses(active);
        } catch (Exception e) {
            log.warn("Синхронизация графика не прошла: {}", e.getMessage());
        }
    }

    /** Живые статусы операций: факт — история выдач симулятора (docs/09 §7 п.3). */
    @Transactional
    public void syncStatuses(ScheduleRun run) {
        List<ScheduleOperation> ops = operations.findByRunIdOrderByIdAsc(run.getId());
        if (ops.isEmpty()) return;

        Map<String, Simulator.DischargeRecord> bySlot = new HashMap<>();
        for (Simulator.DischargeRecord r : simulator.dischargeHistory())
            bySlot.put(slotKey(r.ovenNo(), r.plannedMin()), r);

        double now = simulator.simMin();
        List<ScheduleOperation> changed = new ArrayList<>();
        for (ScheduleOperation op : ops) {
            OperationStatus status = op.getStatus();
            if (status == OperationStatus.DONE || status == OperationStatus.IMPOSSIBLE) continue;

            double plannedMin = SimulatorService.simMinutesOf(op.getPlannedStart());
            Simulator.DischargeRecord fact = bySlot.get(slotKey(op.getOvenNumber(), plannedMin));
            if (fact != null) {
                double delay = fact.actualMin() - fact.plannedMin();
                op.setActualStart(SimulatorService.toPlantTime(fact.actualMin()));
                op.setActualEnd(SimulatorService.toPlantTime(fact.actualMin() + OPERATION_MINUTES));
                op.setDeviationMinutes((int) Math.round(delay));
                if (delay > LATE_TOLERANCE_MIN) {
                    op.setStatus(OperationStatus.OVERDUE);   // выполнено с опозданием — терминально
                } else if (now < fact.actualMin() + OPERATION_MINUTES) {
                    op.setStatus(OperationStatus.IN_PROGRESS); // выдача началась, операция идёт
                } else {
                    op.setStatus(OperationStatus.DONE);
                }
                changed.add(op);
            } else if (op.getOvenNumber() != null && !simulator.ovenAvailable(op.getOvenNumber())) {
                if (status != OperationStatus.IMPOSSIBLE) {
                    op.setStatus(OperationStatus.IMPOSSIBLE);
                    changed.add(op);
                }
            } else if (now > plannedMin + OPERATION_MINUTES + LATE_TOLERANCE_MIN) {
                if (status != OperationStatus.OVERDUE) {
                    op.setStatus(OperationStatus.OVERDUE);
                    changed.add(op);
                }
            } else if (now >= plannedMin && status == OperationStatus.PLANNED) {
                op.setStatus(OperationStatus.IN_PROGRESS);
                changed.add(op);
            }
        }
        if (!changed.isEmpty()) operations.saveAll(changed);
    }

    // ------------------------------------------------------------------
    // ручная правка
    // ------------------------------------------------------------------

    /**
     * Ручная правка времени операции: обязательная причина, пометка manual,
     * запись в журнал и перенос выдачи в симуляторе — с последующими операциями
     * (порядок печей не меняется, H1).
     */
    @Transactional
    public ScheduleOperation manualEdit(long id, ManualEditRequest request) {
        if (request.reason() == null || request.reason().isBlank())
            throw new IllegalArgumentException("причина правки обязательна");
        ScheduleOperation op = operations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("операция %d не найдена".formatted(id)));
        if (op.getActualStart() != null)
            throw new IllegalArgumentException("операция уже выполнена — перенос невозможен");
        LocalDateTime newStart = LocalDateTime.ofInstant(Instant.parse(request.plannedStart()),
                SimulatorService.PLANT_ZONE).truncatedTo(ChronoUnit.MINUTES);
        double newMin = SimulatorService.simMinutesOf(newStart);
        if (newMin <= simulator.simMin())
            throw new IllegalArgumentException("новое время в прошлом: сейчас %s"
                    .formatted(SimulatorService.toPlantTime(simulator.simMin())));

        ScheduleRun active = runs.findTopByStatusOrderByCreatedAtDesc(RunStatus.CALCULATED)
                .orElseThrow(() -> new IllegalArgumentException("нет действующего графика"));
        if (!active.getId().equals(op.getRunId()))
            throw new IllegalArgumentException("операция принадлежит архивному прогону");

        op.setPlannedStart(newStart);
        op.setPlannedEnd(newStart.plusMinutes(OPERATION_MINUTES));
        op.setManual(true);
        op.setNote(request.reason().strip());
        operations.save(op);

        applyPlanFromOperations(active);
        audit.log("MANUAL_SCHEDULE_EDIT", "ScheduleOperation", id,
                "Перенос выдачи П-%d на %s: %s".formatted(
                        op.getOvenNumber(), newStart.toLocalTime(), request.reason().strip()));
        return op;
    }

    /** План из текущих будущих операций действующего прогона — в симулятор. */
    private void applyPlanFromOperations(ScheduleRun run) {
        double now = simulator.simMin();
        List<ScheduleOperation> future = operations.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(op -> op.getActualStart() == null)
                .filter(op -> SimulatorService.simMinutesOf(op.getPlannedStart()) >= now - LATE_TOLERANCE_MIN)
                .sorted(java.util.Comparator.comparing(ScheduleOperation::getPlannedStart))
                .toList();
        if (future.isEmpty()) return;
        double[] plan = new double[future.size()];
        for (int i = 0; i < plan.length; i++)
            plan[i] = Math.max(SimulatorService.simMinutesOf(future.get(i).getPlannedStart()), now);
        simulator.applyPlan(ScheduleAlgorithm.monotone(plan));
    }

    /**
     * Ручные правки предыдущего прогона переносятся в новый: операция остаётся
     * с пометкой manual и своим временем, план алгоритма под неё поджимается
     * (последующие сдвигаются позже, порядок тот же).
     */
    private double[] applyManualCarryOver(double[] plan, ScheduleInput input,
                                          List<ScheduleOperation> manualOps) {
        for (ScheduleOperation manual : manualOps) {
            if (manual.getOvenNumber() == null) continue;
            double wanted = SimulatorService.simMinutesOf(manual.getPlannedStart());
            if (wanted <= simulator.simMin()) continue;
            for (int k = 0; k < input.horizon(); k++) {
                if (input.ovens()[k] == manual.getOvenNumber() && k > 0) {
                    plan[k] = Math.max(plan[k - 1], wanted);
                    break;
                }
            }
        }
        return plan.length == 0 ? plan : ScheduleAlgorithm.monotone(plan);
    }

    private int createOperations(ScheduleRun run, double[] plan, int[] sliceOvens,
                                 List<ScheduleOperation> manualOps) {
        // ручные операции старого прогона переезжают в новый со своим временем
        List<ScheduleOperation> created = new ArrayList<>();
        for (ScheduleOperation manual : manualOps) {
            ScheduleOperation copy = new ScheduleOperation();
            copy.setRunId(run.getId());
            copy.setOven(manual.getOven());
            copy.setOvenNumber(manual.getOvenNumber());
            copy.setOperationType(manual.getOperationType());
            copy.setPlannedStart(manual.getPlannedStart());
            copy.setPlannedEnd(manual.getPlannedEnd());
            copy.setStatus(OperationStatus.PLANNED);
            copy.setPriority(manual.getPriority());
            copy.setManual(true);
            copy.setUrgent(manual.getUrgent());
            copy.setNote(manual.getNote());
            created.add(copy);
        }

        // печи по порядку выдач — тот же срез, по которому строился план
        for (int k = 0; k < plan.length; k++) {
            final int ovenNumber = sliceOvens[k];
            final LocalDateTime start = SimulatorService.toPlantTime(plan[k]);
            ovens.findByNameOven("Печь П-" + ovenNumber).ifPresent(oven -> {
                ScheduleOperation op = new ScheduleOperation();
                op.setRunId(run.getId());
                op.setOven(oven);
                op.setOvenNumber(ovenNumber);
                op.setOperationType("PUSHING");
                op.setPlannedStart(start);
                op.setPlannedEnd(start.plusMinutes(OPERATION_MINUTES));
                op.setStatus(OperationStatus.PLANNED);
                op.setPriority(1);
                op.setManual(false);
                op.setUrgent(false);
                created.add(op);
            });
        }
        operations.saveAll(created);
        return created.size();
    }

    // ------------------------------------------------------------------
    // сравнение алгоритмов на одном входе
    // ------------------------------------------------------------------

    /**
     * Сравнение алгоритмов: один срез входа, один снимок батареи, для каждого
     * алгоритма — офлайн-реплей на копии с тем же seed. Случайные отказы за окном
     * сравнения выключены: сценарий возмущений зафиксирован в снимке, шум не должен
     * забивать эффект алгоритма. Другая схема серийности меняет порядок выдач,
     * поэтому для неё батарея пересобирается в стационаре с тем же seed и тем же
     * модельным временем (реплей той же точки невозможен по построению — порядок другой).
     */
    public CompareResult compare(CompareRequest request) {
        List<String> codes = request.algorithmCodes() == null || request.algorithmCodes().isEmpty()
                ? ScheduleAlgorithms.all().stream().map(ScheduleAlgorithm::code).toList()
                : request.algorithmCodes();
        ru.evraz.koks.sim.SimConfig live = simulator.snapshotState().config();
        ru.evraz.koks.sim.SimConfig silent = new ru.evraz.koks.sim.SimConfig(
                live.ovenCount(), live.seriality(), live.cycleMin(), live.cokingMin(),
                live.toleranceMin(), live.pitchM(), live.pushMin(), live.doorRemoveMin(),
                live.doorInstallMin(), 0.0, live.pushJamRepairMin(), live.takeMin(),
                live.unloadMin(), live.loadMin(), live.towerDistanceM(), 0.0,
                live.ovenFaultRepairMin(), live.seed());
        Simulator.BatteryState snapshot = simulator.snapshotState().withConfig(silent);
        ScheduleInput input = simulator.planInput(Integer.MAX_VALUE);
        double from = snapshot.simMin();
        double window = request.windowMin() == null || request.windowMin() < 60
                ? DEFAULT_COMPARE_WINDOW_MIN : request.windowMin();

        boolean otherScheme = request.seriality() != null
                && !request.seriality().strip().equals(simulator.seriality());
        Simulator.BatteryState baseSnapshot = null;
        if (otherScheme) {
            ru.evraz.koks.sim.SimConfig reschema = new ru.evraz.koks.sim.SimConfig(
                    silent.ovenCount(), request.seriality().strip(), silent.cycleMin(), silent.cokingMin(),
                    silent.toleranceMin(), silent.pitchM(), silent.pushMin(), silent.doorRemoveMin(),
                    silent.doorInstallMin(), silent.pushJamProb(), silent.pushJamRepairMin(), silent.takeMin(),
                    silent.unloadMin(), silent.loadMin(), silent.towerDistanceM(), silent.ovenFaultPerHour(),
                    silent.ovenFaultRepairMin(), silent.seed());
            Simulator base = new ru.evraz.koks.sim.Simulator(reschema);
            base.advance((long) Math.ceil(from));
            baseSnapshot = base.exportState();                  // каждый алгоритм — с чистой точки
        }

        List<CompareRunDto> runRows = new ArrayList<>();
        for (String code : codes) {
            ScheduleAlgorithm algo = ScheduleAlgorithms.byCode(code);
            Simulator.BatteryState base = otherScheme ? baseSnapshot : snapshot;
            Metrics.Result m = replay(base, algo, from, from + window);
            runRows.add(new CompareRunDto(algo.code(), algo.title(), otherScheme ? request.seriality().strip() : simulator.seriality(),
                    Map.of("late_operations", m.lateCount(),
                            "total_lateness_min", Math.round(m.totalDelayMin()),
                            "uniformity", finiteOrNull(m.oeeUniformityPct()),
                            "idle_min", Math.round(m.machineIdleMin() + m.ovenWaitMin()),
                            "max_overhold_min", Math.round(m.maxOverholdMin()),
                            "interval_spread_min", finiteOrNull(m.intervalStdMin()),
                            "constraint_violations", m.violations())));
        }
        String note = otherScheme
                ? "схема %s: батарея в стационаре на той же модельной минуте, тот же seed — порядок выдач другой, реплей невозможен"
                        .formatted(request.seriality().strip())
                : "реплей с той же точки: тот же снимок и seed, случайные отказы за окном выключены";
        audit.log("SCHEDULE_COMPARE", "ScheduleRun", null,
                "Сравнение на одном входе: %s (%s)".formatted(String.join(", ", codes), note));
        return new CompareResult(criteria(), runRows, note);
    }

    /**
     * Офлайн-реплей алгоритма на копии батареи: план применяется от текущего
     * состояния и пересчитывается на границе каждого плана — как живая система
     * после возмущения. Без этого голая сетка за пределами первого плана бьёт
     * по печам с фазовым долгом и забивает разницу алгоритмов.
     */
    private Metrics.Result replay(Simulator.BatteryState snapshot, ScheduleAlgorithm algo,
                                  double from, double to) {
        Simulator copy = Simulator.restore(snapshot);
        while (copy.simMinutes() < to - 1e-9) {
            ScheduleInput input = ScheduleInput.of(copy, Integer.MAX_VALUE);
            double[] plan = algo.build(input);
            copy.setPlan(plan);
            long target = (long) Math.min(Math.ceil(plan[plan.length - 1]), Math.ceil(to));
            copy.advance(Math.max(1, target - (long) copy.simMinutes()));
        }
        return Metrics.compute(copy, from, to,
                simulator.toleranceMin(), simulator.cokingMin(), simulator.loadMin());
    }

    public record CompareRunDto(String algorithmCode, String algorithmName, String seriality,
                                Map<String, Object> values) { }

    public record CriterionDto(String code, String label, String better, String unit) { }

    public record CompareResult(List<CriterionDto> criteria, List<CompareRunDto> runs,
                                String inputNote) { }

    private static List<CriterionDto> criteria() {
        return List.of(
                new CriterionDto("late_operations", "Просрочено операций", "lower", "шт"),
                new CriterionDto("total_lateness_min", "Суммарная просрочка", "lower", "мин"),
                new CriterionDto("uniformity", "Равномерность ОЕЭ", "higher", "%"),
                new CriterionDto("idle_min", "Простой", "lower", "мин"),
                new CriterionDto("max_overhold_min", "Макс. передержка", "lower", "мин"),
                new CriterionDto("interval_spread_min", "Разброс интервалов", "lower", "мин"),
                new CriterionDto("constraint_violations", "Нарушений (тех.)", "lower", "шт"));
    }

    // ------------------------------------------------------------------
    // чтение
    // ------------------------------------------------------------------

    public record RunDto(String id, String parentId, String algorithm, String algorithmTitle,
                         String seriality, Long seed, String reason, String status,
                         String inputHash, long windowFromMin, long windowToMin,
                         String createdAt, int totalOperations, Map<String, Object> metrics) { }

    public List<RunDto> list() {
        return runs.findAllByOrderByCreatedAtDesc().stream()
                .map(run -> toDto(run, -1))
                .toList();
    }

    public RunDto get(String id) {
        ScheduleRun run = runs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("прогон %s не найден".formatted(id)));
        return toDto(run, operations.findByRunIdOrderByIdAsc(id).size());
    }

    public List<ScheduleOperation> runOperations(String id) {
        runs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("прогон %s не найден".formatted(id)));
        return operations.findByRunIdOrderByIdAsc(id);
    }

    /** Действующий прогон, если есть. */
    public java.util.Optional<RunDto> activeRun() {
        return runs.findTopByStatusOrderByCreatedAtDesc(RunStatus.CALCULATED).map(run -> toDto(run,
                operations.findByRunIdOrderByIdAsc(run.getId()).size()));
    }

    // ------------------------------------------------------------------
    // служебное
    // ------------------------------------------------------------------

    private RunDto toDto(ScheduleRun run, int totalOperations) {
        Map<String, Object> metrics = run.getMetricsJson() == null ? Map.of() : fromJson(run.getMetricsJson());
        ScheduleAlgorithm algo = ScheduleAlgorithms.byCode(run.getAlgorithm());
        return new RunDto(run.getId(), run.getParentId(), run.getAlgorithm(), algo.title(),
                run.getSeriality(), run.getSeed(), run.getReason(), run.getStatus().name(),
                run.getInputHash(), run.getWindowFromMin(), run.getWindowToMin(),
                run.getCreatedAt().toString(), totalOperations, metrics);
    }

    private static String slotKey(Integer ovenNumber, double plannedMin) {
        return ovenNumber + ":" + Math.round(plannedMin);
    }

    private static String inputHash(ScheduleInput input, ScheduleAlgorithm algo) {
        String source = "%s|%d|%d|%s|%s".formatted(algo.code(),
                (long) input.nowMin(), (long) input.firstSlotMin(), input.horizon(),
                input.minIntervalMin());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> metricsJson(Metrics.Result m, Simulator copy, Simulator.BatteryState snapshot) {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("totalDischarges", m.totalDischarges());
        json.put("lateCount", m.lateCount());
        json.put("totalDelayMin", finiteOrNull(m.totalDelayMin()));
        json.put("oeeUniformityPct", finiteOrNull(m.oeeUniformityPct()));
        json.put("oeeSpreadPct", finiteOrNull(m.oeeSpreadPct()));
        json.put("machineIdleMin", finiteOrNull(m.machineIdleMin()));
        json.put("ovenWaitMin", finiteOrNull(m.ovenWaitMin()));
        json.put("violations", m.violations());
        json.put("calcUniformity", finiteOrNull(m.calcUniformity()));
        json.put("intervalStdMin", finiteOrNull(m.intervalStdMin()));
        json.put("maxOverholdMin", finiteOrNull(m.maxOverholdMin()));
        json.put("skipped", copy.skipped() - snapshot.skipped());
        int excluded = 0;
        for (int p = 1; p <= copy.ovenCount(); p++)
            if (!copy.ovenAvailable(p)) excluded++;
        json.put("excluded", excluded);
        return json;
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? Math.round(value * 100) / 100.0 : null;
    }

    private String toJson(Map<String, Object> json) {
        try {
            return mapper.writeValueAsString(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("метрики не сериализуются", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
