package org.example.coke_factory.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.evraz.koks.sim.Event;
import ru.evraz.koks.sim.MachineKind;
import ru.evraz.koks.sim.OvenState;
import ru.evraz.koks.sim.SimConfig;
import ru.evraz.koks.sim.Simulator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Обёртка над симулятором коксовой батареи (код Вани, пакет ru.evraz.koks.sim).
 *
 * <p>Держит один экземпляр {@link Simulator} в памяти и двигает его раз в секунду.
 * Движок не потокобезопасен, поэтому все публичные методы — synchronized.
 *
 * <p>Время: внутри симулятора минуты от 0, наружу — ISO UTC от якоря T0
 * (2026-10-05T01:00:00Z = 08:00 по Новосибирску, начало смены).
 *
 * <p>Скорость: speedFactor = ускорение времени, как «x60» в шапке интерфейса.
 * x60 означает «1 секунда реального времени = 1 минута симуляции».
 */
@Service
public class SimulatorService {

    /** Часовой пояс предприятия — в нём показываем время и пишем журнал. */
    public static final ZoneId PLANT_ZONE = ZoneId.of("Asia/Novosibirsk");

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");

    private SimConfig config = SimConfig.defaults();
    private Simulator sim = new Simulator(config);
    private boolean running = false;     // после старта приложения симуляция на паузе, ПУСК запускает
    private int speedFactor = 60;        // x60: 1 с = 1 мин симуляции
    private double carryMinutes = 0;     // дробный остаток минут между тиками
    /** Поколение живого симулятора: растёт при каждой замене экземпляра (сброс, загрузка
     *  снимка, смена seed/схемы, импорт печей). План прежнего поколения к новому не относится. */
    private long generation = 0;

    // ------------------------------------------------------------------
    // тик симуляции
    // ------------------------------------------------------------------

    @Scheduled(fixedRate = 1000)
    public synchronized void tick() {
        if (!running) return;
        // за 1 реальную секунду проходит speedFactor/60 минут симуляции
        carryMinutes += speedFactor / 60.0;
        long whole = (long) carryMinutes;
        if (whole > 0) {
            carryMinutes -= whole;
            sim.advance(whole);
        }
    }

    // ------------------------------------------------------------------
    // чтение состояния
    // ------------------------------------------------------------------

    public synchronized BatteryDto battery() {
        return new BatteryDto("Б-1", "Коксовая батарея №1",
                sim.ovenCount(), config.seriality(), config.cycleMin(),
                config.cokingMin(), round2(sim.deltaMin()),
                config.toleranceMin(), "08:00");
    }

    public synchronized StateDto state() {
        return new StateDto(
                sim.simMinutes(), iso(sim.simMinutes()), running, speedFactor,
                new Counters(sim.discharges(), sim.lateCount(), sim.overholdCount(),
                        sim.pushJams(), sim.ovenFaults()),
                sim.order()[sim.nextIssue() % sim.ovenCount()],
                sim.discharges() < 2 ? null : round1(sim.avgIntervalMin()),
                config.pushJamProb(), config.ovenFaultPerHour(), config.seed());
    }

    /**
     * Атомарный снимок оператора: время, печи и события относятся к одной
     * модельной минуте. Отдельные параллельные HTTP-запросы могли получить
     * разные поколения печей на границе выгрузки и загрузки.
     */
    public synchronized SnapshotDto snapshot() {
        return new SnapshotDto(state(), battery(), ovens(), machines(),
                feed(20), events(200), analytics(20));
    }

    public synchronized List<OvenDto> ovens() {
        List<OvenDto> out = new ArrayList<>();
        for (int n = 1; n <= sim.ovenCount(); n++) {
            OvenState st = sim.state(n);
            double progress = switch (st) {
                case COKING -> {
                    double a = sim.loadDoneAt(n);
                    double b = sim.readyAt(n);
                    yield b > a ? clamp((sim.simMinutes() - a) / (b - a) * 100) : 100;
                }
                case READY, OVERHOLD -> 100;
                case EMPTY, FAULT, UNAVAILABLE -> 0;
            };
            double deviation = st == OvenState.OVERHOLD
                    ? Math.max(0, sim.simMinutes() - sim.readyAt(n)) : 0;
            out.add(new OvenDto(n, apiState(st), st.label, round1(progress),
                    round1(deviation), iso(sim.readyAt(n)), iso(sim.loadDoneAt(n)), ovenSeverity(st)));
        }
        return out;
    }

    public synchronized List<MachineDto> machines() {
        long now = sim.simMinutes();
        List<Simulator.MachineStatus> all = sim.machineStatus();
        return all.stream().map(m -> {
            // pos в симуляторе — позиция в КОНЦЕ последней задачи машины. У тушильного вагона
            // это всегда башня, поэтому для экрана считаем, где он находится прямо сейчас.
            double pos = "ТВ".equals(m.code()) ? quenchCarPositionNow(m, now) : m.pos();
            boolean atBattery = pos <= sim.ovenCount() + 1e-9;
            Integer oven = atBattery ? (int) Math.round(pos) : null;
            Integer x = oven == null ? null
                    : Math.max(0, Math.min(sim.ovenCount() - 1, oven - 1));
            return new MachineDto(m.code(), machineType(m.code()), m.name(),
                    m.runningAt(now) ? "running" : "idle", round2(pos),
                    oven, x, location(m.code(), atBattery), iso(m.freeAt()),
                    (int) Math.round(m.utilization() * 100),
                    m.down(), downUntilIso(m));
        }).toList();
    }

    /** Конец простоя машины по часам предприятия; «до возврата» — null. */
    private static String downUntilIso(Simulator.MachineStatus m) {
        if (!m.down() || m.downUntil() >= Simulator.FAR) return null;
        return iso(m.downUntil());
    }

    /**
     * Где тушильный вагон сейчас. Поездка вагона: стоит у печи и принимает кокс (takeMin),
     * едет к башне, разгружается у башни (unloadMin). Свободный вагон стоит у башни.
     */
    private double quenchCarPositionNow(Simulator.MachineStatus wagon, long now) {
        double tower = sim.towerPos();
        if (!wagon.runningAt(now) || sim.lastDischargeOven() == 0) return tower;

        // Цель последней выдачи неизменна, даже когда выталкиватель получил следующую работу.
        double ovenPos = sim.lastDischargeOven();
        double remaining = wagon.freeAt() - now;
        double unload = config.unloadMin();
        double travel = MachineKind.QUENCH_CAR.travelMin(ovenPos, tower, config.pitchM());

        if (travel <= 0 || remaining <= unload) return tower;
        if (remaining <= unload + travel) {
            double fraction = (remaining - unload) / travel;
            return tower - (tower - ovenPos) * fraction;
        }
        return ovenPos;
    }

    /** Доказуемые показатели: только совершившиеся выдачи и накопленная загрузка машин. */
    public synchronized AnalyticsDto analytics(int limit) {
        List<Simulator.DischargeRecord> history = sim.dischargeHistory();
        int total = history.size();
        long delayed = history.stream()
                .filter(r -> r.actualMin() - r.plannedMin() > config.toleranceMin())
                .count();
        double sumDelay = history.stream()
                .mapToDouble(r -> Math.max(0, r.actualMin() - r.plannedMin())).sum();
        double maxDelay = history.stream()
                .mapToDouble(r -> Math.max(0, r.actualMin() - r.plannedMin()))
                .max().orElse(0);
        int first = Math.max(0, total - Math.max(0, Math.min(limit, 200)));
        List<OperationActualDto> recent = history.subList(first, total).stream()
                .map(r -> {
                    double delay = Math.max(0, r.actualMin() - r.plannedMin());
                    return new OperationActualDto(r.ovenNo(), iso(r.plannedMin()),
                            iso(r.actualMin()), round1(delay),
                            delay <= config.toleranceMin());
                }).toList();
        List<MachineUtilizationDto> utilization = sim.machineStatus().stream()
                .map(m -> new MachineUtilizationDto(machineType(m.code()), m.name(),
                        (int) Math.round(m.utilization() * 100), m.tasksDone()))
                .toList();
        return new AnalyticsDto(iso(sim.simMinutes()), total, total - (int) delayed,
                (int) delayed, total == 0 ? null : round1(100.0 * (total - delayed) / total),
                total == 0 ? null : round1(sumDelay / total), round1(maxDelay),
                total < 2 ? null : round1(sim.avgIntervalMin()), round2(sim.deltaMin()),
                recent, utilization);
    }

    public synchronized FeedDto feed(int limit) {
        return feedDto(limit);
    }

    public synchronized List<EventDto> events(int limit) {
        // LOADED симулятор записывает заранее (на конец загрузки) — «из будущего» не показываем
        long now = sim.simMinutes();
        List<Event> all = sim.events().stream().filter(e -> e.simMin() <= now).toList();
        int n = Math.max(0, Math.min(limit, all.size()));
        List<EventDto> out = new ArrayList<>(n);
        for (int i = all.size() - 1; i >= all.size() - n; i--) {
            Event e = all.get(i);
            out.add(new EventDto(iso(e.simMin()), e.simMin(), e.type().name(),
                    e.ovenNo(), e.detail(), eventSeverity(e)));
        }
        return out;
    }

    /** Текущее время симуляции по часам предприятия (для записей журнала действий). */
    public synchronized LocalDateTime simLocalTime() {
        return LocalDateTime.ofInstant(T0.plusSeconds(sim.simMinutes() * 60), PLANT_ZONE);
    }

    // ------------------------------------------------------------------
    // управление
    // ------------------------------------------------------------------

    public synchronized StateDto control(ControlRequest r) {
        switch (r.action() == null ? "" : r.action()) {
            case "start" -> running = true;
            case "pause" -> running = false;
            case "step" -> {
                running = false;
                sim.advance(r.minutes() == null ? 1 : Math.max(1, r.minutes()));
            }
            case "reset" -> {
                sim = new Simulator(config);
                generation++;
                running = false;
                carryMinutes = 0;
            }
            default -> throw new IllegalArgumentException("action: start|pause|step|reset");
        }
        return state();
    }

    public synchronized StateDto configure(ConfigRequest r) {
        if (r.speedFactor() != null) {
            speedFactor = Math.max(1, Math.min(600, r.speedFactor()));
        }

        // схема серийности проверяется построением порядка: нерабочая схема — ошибка
        String seriality = config.seriality();
        if (r.seriality() != null && !r.seriality().strip().equals(seriality)) {
            ru.evraz.koks.sim.Seriality.Scheme.parse(r.seriality());  // бросит исключение, если схема нерабочая
            seriality = r.seriality().strip();
        }

        boolean rebuild = r.seed() != null || r.pushJamProb() != null
                || r.ovenFaultPerHour() != null || r.seriality() != null;
        if (rebuild) {
            double jam = r.pushJamProb() != null
                    ? Math.max(0, Math.min(1, r.pushJamProb())) : config.pushJamProb();
            double heat = r.ovenFaultPerHour() != null
                    ? Math.max(0, r.ovenFaultPerHour()) : config.ovenFaultPerHour();
            // SimConfig неизменяемый, поэтому собираем новый и пересоздаём симулятор
            config = new SimConfig(
                    config.ovenCount(), seriality, config.cycleMin(),
                    config.cokingMin(), config.toleranceMin(), config.pitchM(),
                    config.pushMin(), config.doorRemoveMin(), config.doorInstallMin(),
                    jam, config.pushJamRepairMin(), config.takeMin(), config.unloadMin(),
                    config.loadMin(), config.towerDistanceM(), heat,
                    config.ovenFaultRepairMin(),
                    r.seed() != null ? r.seed() : config.seed());
            sim = new Simulator(config);
            generation++;
            running = false;
            carryMinutes = 0;
        }
        return state();
    }

    public synchronized StateDto inject(FailureRequest r) {
        switch (r.type() == null ? "" : r.type()) {
            case "pushJam" -> sim.injectFailure(Simulator.FailureType.PUSH_JAM);
            case "ovenHeating" -> {
                if (r.ovenNumber() != null) sim.injectOvenHeatingFault(r.ovenNumber());
                else sim.injectFailure(Simulator.FailureType.OVEN_HEATING);
            }
            default -> throw new IllegalArgumentException("type: pushJam|ovenHeating");
        }
        return state();
    }

    /** Полный снимок батареи — то, что уходит в файл при выгрузке состояния. */
    public synchronized Simulator.BatteryState exportState() {
        return sim.exportState();
    }

    /**
     * Загрузить состояние из снимка: время, печи, машины и журнал событий заменяются
     * содержимым файла, симуляция встаёт на паузу. Журнал действий оператора живёт
     * в базе и не откатывается. При ошибке в файле текущий симулятор остаётся нетронутым.
     */
    public synchronized StateDto importState(Simulator.BatteryState state) {
        Simulator restored = Simulator.restore(state);
        config = restored.config();
        sim = restored;
        generation++;
        running = false;
        carryMinutes = 0;
        return state();
    }

    /** «Сформировать график». Пока снимок текущего порядка серийности и прогноза. */
    public synchronized ScheduleDto generate(int limit) {
        FeedDto f = feedDto(limit);
        return new ScheduleDto(f.generatedAt(), "b1_fifo", sim.ovenCount(), f.items());
    }

    // ------------------------------------------------------------------
    // мост к планировщику и доступности оборудования (docs/05 §7, docs/10)
    // ------------------------------------------------------------------

    /** Срез входа планировщика из живого прогона: печи, готовность, физический предел. */
    public synchronized ru.evraz.koks.sim.planner.ScheduleInput planInput(int horizon) {
        return ru.evraz.koks.sim.planner.ScheduleInput.of(sim, horizon);
    }

    /** То же с переопределением шага сетки (ручка «допустимый шаг между выдачами»). */
    public synchronized ru.evraz.koks.sim.planner.ScheduleInput planInput(int horizon,
                                                                          double stepMinOverride) {
        return ru.evraz.koks.sim.planner.ScheduleInput.of(sim, horizon, stepMinOverride);
    }

    /** Установить план выдач в живой прогон: выдача идёт по плану, а не по сетке k·Δt. */
    public synchronized void applyPlan(double[] plannedStartsMin) {
        sim.setPlan(plannedStartsMin);
    }

    /** Копия текущего плана живого прогона. */
    public synchronized double[] currentPlan() {
        return sim.plan();
    }

    /** Полная копия снимка состояния — для офлайн-реплея планировщика. */
    public synchronized Simulator.BatteryState snapshotState() {
        return sim.exportState();
    }

    /** Фактические выдачи: печь, план, факт, готовность. */
    public synchronized List<Simulator.DischargeRecord> dischargeHistory() {
        return sim.dischargeHistory();
    }

    /** Возмущений с начала прогона: изменилось — план стоит пересчитать. */
    /** Поколение живого симулятора (см. поле {@code generation}). */
    public synchronized long generation() {
        return generation;
    }

    public synchronized long revision() {
        return sim.revision();
    }

    public synchronized int ovenCount() {
        return sim.ovenCount();
    }

    /** Печь доступна (в серии). */
    public synchronized boolean ovenAvailable(int ovenNo) {
        return sim.ovenAvailable(ovenNo);
    }

    /** Вывести печь из серии или вернуть: слот пропускается, порядок не меняется. */
    public synchronized void setOvenAvailable(int ovenNo, boolean available, String reason) {
        sim.setOvenAvailable(ovenNo, available, reason);
    }

    /** Машина встаёт в простой: на minutes минут или до возврата (minutes <= 0). */
    public synchronized void setMachineDown(MachineKind kind, double minutes) {
        sim.setMachineDown(kind, minutes);
    }

    /** Вернуть машину в работу. */
    public synchronized void setMachineUp(MachineKind kind) {
        sim.setMachineUp(kind);
    }

    /** Машина в простое в текущий момент. */
    public synchronized boolean machineDown(MachineKind kind) {
        return sim.machineDownAt(kind, sim.simMinutes());
    }

    /** Схема серийности текущей конфигурации: 9-2 / 5-2 / 2-1. */
    public synchronized String seriality() {
        return config.seriality();
    }

    /** Нормативный период коксования, мин. */
    public synchronized double cokingMin() {
        return config.cokingMin();
    }

    /** Норматив загрузки камеры, мин. */
    public synchronized double loadMin() {
        return config.loadMin();
    }

    /** Допуск опоздания/передержки, мин. */
    public synchronized double toleranceMin() {
        return config.toleranceMin();
    }

    /** Модельная минута прогона. */
    public synchronized double simMin() {
        return sim.simMinutes();
    }
    /** Нормативный шаг выдачи Δt = T_цикл / N, мин. */
    public synchronized double deltaMin() {
        return sim.deltaMin();
    }

    /**
     * Пересобрать батарею под новый справочник печей: новый SimConfig с другим
     * числом печей, новый симулятор в стационаре; перечисленные печи выводятся
     * из серии. Прогон и журнал событий начинаются заново — это импорт состава,
     * а не правка живого графика.
     */
    public synchronized void rebuildBattery(int ovenCount, List<Integer> unavailableOvens) {
        config = new SimConfig(
                Math.max(2, ovenCount), config.seriality(), config.cycleMin(),
                config.cokingMin(), config.toleranceMin(), config.pitchM(),
                config.pushMin(), config.doorRemoveMin(), config.doorInstallMin(),
                config.pushJamProb(), config.pushJamRepairMin(), config.takeMin(),
                config.unloadMin(), config.loadMin(), config.towerDistanceM(),
                config.ovenFaultPerHour(), config.ovenFaultRepairMin(), config.seed());
        sim = new Simulator(config);
        generation++;
        running = false;
        carryMinutes = 0;
        for (Integer oven : unavailableOvens)
            sim.setOvenAvailable(oven, false, "импорт справочника: печь в ремонте");
    }

    // ------------------------------------------------------------------
    // DTO (ровно по контракту docs/08)
    // ------------------------------------------------------------------

    public record SnapshotDto(StateDto state, BatteryDto battery,
                              List<OvenDto> ovens, List<MachineDto> machines,
                              FeedDto feed, List<EventDto> events,
                              AnalyticsDto analytics) { }

    public record OperationActualDto(int ovenNumber, String plannedAt,
                                     String actualAt, double delayMin, boolean onTime) { }

    public record MachineUtilizationDto(String type, String name,
                                        int utilizationPct, int tasksDone) { }

    public record AnalyticsDto(String generatedAt, int totalOperations,
                               int onTimeOperations, int delayedOperations,
                               Double onTimePct, Double avgDelayMin, double maxDelayMin,
                               Double avgIntervalMin, double normIntervalMin,
                               List<OperationActualDto> recentOperations,
                               List<MachineUtilizationDto> machineUtilization) { }

    public record BatteryDto(String code, String name, int ovenCount,
                             String serialityScheme, double cycleMin, double cokingMin,
                             double deltaMin, double toleranceMin, String shiftStartLocal) {
    }

    public record Counters(int discharges, int late, int overhold,
                           int pushJams, int ovenFaults) {
    }

    public record StateDto(long simMinutes, String simTime, boolean running,
                           int speedFactor, Counters counters, int nextOvenNumber,
                           Double avgIntervalMin,
                           double pushJamProb, double ovenFaultPerHour, long seed) {
    }

    public record OvenDto(int number, String state, String label, double progressPct,
                          double deviationMin, String readyAt, String cokingStartedAt, String severity) {
    }

    public record MachineDto(String code, String type, String name, String status,
                             double pos, Integer currentOvenNumber, Integer currentXIndex,
                             String location, String busyUntil, int utilizationPct,
                             boolean down, String downUntil) {
    }

    public record FeedItemDto(int ovenNumber, String eventType, String title,
                              String plannedAt, String predictedAt, double deviationMin,
                              String severity, String relativeLabel) {
    }

    public record FeedDto(String generatedAt, List<FeedItemDto> items) {
    }

    public record ScheduleDto(String generatedAt, String algorithmCode,
                              int totalOperations, List<FeedItemDto> items) {
    }

    public record EventDto(String simTime, long simMinutes, String type, int ovenNumber,
                           String title, String severity) {
    }

    public record ControlRequest(String action, Long minutes) {
    }

    public record ConfigRequest(Integer speedFactor, Long seed,
                                Double pushJamProb, Double ovenFaultPerHour,
                                String seriality) {
    }

    public record FailureRequest(String type, Integer ovenNumber) {
    }

    // ------------------------------------------------------------------
    // маппинг и утилиты
    // ------------------------------------------------------------------

    private FeedDto feedDto(int limit) {
        int n = Math.max(1, Math.min(limit, sim.ovenCount()));
        long now = sim.simMinutes();
        List<FeedItemDto> items = sim.forecast(n).stream().map(p -> {
            double dev = p.predictedStartMin() - p.plannedMin();
            return new FeedItemDto(p.ovenNo(), "discharge_start",
                    "П-%d · выдача кокса".formatted(p.ovenNo()),
                    iso(p.plannedMin()), iso(p.predictedStartMin()), round1(dev),
                    dev > config.toleranceMin() ? "warning" : "info",
                    relative(p.predictedStartMin() - now));
        }).toList();
        return new FeedDto(iso(now), items);
    }

    private static String apiState(OvenState s) {
        return switch (s) {
            case EMPTY -> "discharging";
            case COKING -> "coking";
            case READY -> "ready";
            case OVERHOLD -> "overhold";
            case FAULT -> "fault";
            case UNAVAILABLE -> "unavailable";
        };
    }

    private static String ovenSeverity(OvenState s) {
        return switch (s) {
            case OVERHOLD -> "warning";
            case FAULT -> "emergency";
            default -> "info";
        };
    }

    private static String eventSeverity(Event e) {
        return switch (e.type()) {
            case LATE, OVERHOLD, SKIPPED, EXCLUDED -> "warning";
            case FAULT -> e.detail().contains("обогрев") ? "emergency" : "critical";
            case MACHINE_DOWN -> "critical";
            default -> "info";
        };
    }

    private static String machineType(String code) {
        return switch (code) {
            case "КВ" -> "pusher";
            case "ДСМ" -> "doorMachine";
            case "ТВ" -> "quenchCar";
            case "УВ" -> "loadingCar";
            default -> "unknown";
        };
    }

    private static String location(String code, boolean atBattery) {
        if (atBattery) return "battery";
        return switch (code) {
            case "ТВ" -> "quenchTower";
            case "УВ" -> "coalTower";
            default -> "battery";
        };
    }

    static String iso(double min) {
        return T0.plusSeconds(Math.round(min * 60)).toString();
    }

    static String iso(long min) {
        return iso((double) min);
    }

    /** Модельная минута → время по часам предприятия (для планов операций в БД). */
    public static LocalDateTime toPlantTime(double simMinutes) {
        return LocalDateTime.ofInstant(T0.plusSeconds(Math.round(simMinutes * 60)), PLANT_ZONE);
    }

    /** Время по часам предприятия → модельная минута (для ручной правки плана). */
    public static double simMinutesOf(LocalDateTime plantTime) {
        LocalDateTime anchor = LocalDateTime.ofInstant(T0, PLANT_ZONE);
        return java.time.Duration.between(anchor, plantTime).toSeconds() / 60.0;
    }

    private static String relative(double d) {
        if (d <= 0.5) return "сейчас";
        long m = Math.round(d);
        return m < 60 ? "через %d мин".formatted(m)
                : "через %d ч %d мин".formatted(m / 60, m % 60);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
