package ru.evraz.koks.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Симулятор коксовой батареи v0.1 — событийное ядро в памяти с машинами
 * как отдельными сущностями.
 *
 * <p>Модель:
 * <ul>
 *   <li>выдачи следуют в порядке, заданном серийностью (docs/05 §2),
 *       номинальный слот k-й выдачи = k·Δt, Δt = T_цикл / N;</li>
 *   <li>комплект машин: коксовыталкиватель (КВ), двересъёмная машина (ДСМ),
 *       тушильный вагон (ТВ), углезагрузочный вагон (УВ). Каждая машина
 *       имеет позицию вдоль батареи, свою кинематику и занятость;</li>
 *   <li>выдача печи p: ДСМ снимает дверь, КВ и ТВ приезжают к печи. Начало
 *       выталкивания = max(слот, готовность печи, готовность КВ, ТВ и ДСМ).
 *       Отказ выталкивания (тугой ход) затягивает операцию — машины заняты
 *       дольше, поток выдач замедляется;</li>
 *   <li>после выталкивания печь пуста (EMPTY); ТВ везёт пирог к тушильной
 *       башне и разгружается; УВ берёт шихту у угольной башни и загружает
 *       камеру; ДСМ ставит дверь. Коксование начинается с конца загрузки;</li>
 *   <li>отказ обогрева камеры: печь в FAULT до ремонта, готовность сдвигается.</li>
 * </ul>
 * Машины — узкое горлышко: если машина занята, следующая выдача/загрузка
 * ждёт её, печи получают передержку.
 * Все времена — в минутах симуляции. Симуляция детерминирована по seed.
 */
public final class Simulator {

    private final SimConfig cfg;
    private final int[] order;               // печи в порядке выдачи за оборот
    private final double deltaMin;           // Δt выдачи = cycle / N
    private final Random rng;

    // Машины комплекта
    private final Machine doorMachine;       // ДСМ
    private final Machine pusher;            // КВ
    private final Machine quenchCar;         // ТВ
    private final Machine loadingCar;        // УВ

    // Состояние печей (индекс = номер печи 1..N, [0] не используется)
    private final double[] readyAt;          // момент готовности печи к выдаче
    private final double[] heatRecoverAt;    // до этого момента печь в FAULT по обогреву
    private final double[] nextHeatFaultAt;  // запланированный момент следующего отказа обогрева
    private final double[] dischargeAt;      // факт выдачи (начало выталкивания)
    private final double[] loadDoneAt;       // факт окончания загрузки камеры

    private final List<Event> events = new ArrayList<>();

    private long simMin = 0;
    private int nextIssue = 0;

    private int discharges, lateCount, overholdCount, pushJams, ovenFaults;

    public Simulator(SimConfig cfg) {
        this.cfg = cfg;
        this.order = Seriality.dischargeOrder(cfg.ovenCount(), Seriality.Scheme.parse(cfg.seriality()));
        this.deltaMin = cfg.cycleMin() / cfg.ovenCount();
        this.rng = new Random(cfg.seed());
        this.readyAt = new double[cfg.ovenCount() + 1];
        this.heatRecoverAt = new double[cfg.ovenCount() + 1];
        this.nextHeatFaultAt = new double[cfg.ovenCount() + 1];
        this.dischargeAt = new double[cfg.ovenCount() + 1];
        this.loadDoneAt = new double[cfg.ovenCount() + 1];

        double tower = towerPos();
        this.doorMachine = new Machine(MachineKind.DOOR_MACHINE, "Двересъёмная машина", 1);
        this.pusher = new Machine(MachineKind.PUSHING, "Коксовыталкиватель", 1);
        this.quenchCar = new Machine(MachineKind.QUENCH_CAR, "Тушильный вагон", tower);
        this.loadingCar = new Machine(MachineKind.LOADING_CAR, "Углезагрузочный вагон", tower);

        // Инициализация: батарея в стационаре. Печь на позиции k выдачного
        // потока готова к слоту с запасом slack = цикл − коксование −
        // работы машин после выдачи (выталкивание + загрузка).
        double slack = Math.max(0, cfg.cycleMin() - cfg.cokingMin() - cfg.pushMin() - cfg.loadMin());
        for (int k = 0; k < order.length; k++) {
            int p = order[k];
            loadDoneAt[p] = (k + 1) * deltaMin - slack - cfg.cokingMin();
            dischargeAt[p] = loadDoneAt[p] - cfg.pushMin() - cfg.loadMin();
            readyAt[p] = (k + 1) * deltaMin - slack;
            nextHeatFaultAt[p] = sampleHeatFault(0);
        }
    }

    /** Позиция тушильной и угольной башни — за торцом батареи, в номерах печей. */
    public double towerPos() {
        return cfg.ovenCount() + cfg.towerDistanceM() / cfg.pitchM();
    }

    /** Прогнать симуляцию вперёд на minutes минут симуляции. */
    public void advance(long minutes) {
        double target = simMin + minutes;
        while (true) {
            int p = order[nextIssue % order.length];
            double planned = (nextIssue + 1) * deltaMin;

            // Выдача не раньше слота, готовности печи и готовности машин
            // (они могут быть заняты предыдущими задачами).
            double tDischarge = pushStartFor(p, planned);

            int heatP = earliestHeatFault();
            double tHeat = heatP < 0 ? Double.MAX_VALUE : nextHeatFaultAt[heatP];
            int recP = earliestRecovery();
            double tRec = recP < 0 ? Double.MAX_VALUE : heatRecoverAt[recP];

            double tNext = Math.min(tDischarge, Math.min(tHeat, tRec));
            if (tNext > target) break;

            if (tRec <= tDischarge && tRec <= tHeat) {
                events.add(new Event(m(tRec), Event.Type.RECOVERED, recP, "отказ устранён, печь в работе"));
                simMin = m(tRec);
            } else if (tHeat <= tDischarge) {
                applyHeatFault(heatP, tHeat);
            } else {
                doDischarge(p, planned);
            }
        }
        simMin = m(target);
    }

    // --- выдача печи ---

    /** Момент начала выталкивания для выдачи печи p по плану planned. */
    private double pushStartFor(int p, double planned) {
        double pitch = cfg.pitchM();
        double t = Math.max(planned, readyAt[p]);
        t = Math.max(t, pusher.readyAt(p, pitch));
        t = Math.max(t, quenchCar.readyAt(p, pitch));
        // дверь должна быть снята до начала выталкивания
        t = Math.max(t, doorMachine.readyAt(p, pitch) + cfg.doorRemoveMin());
        return t;
    }

    private void doDischarge(int p, double planned) {
        double pitch = cfg.pitchM();
        double pushStart = pushStartFor(p, planned);

        // Отказ выталкивания: тугой ход, машина занята дольше.
        double pushDur = cfg.pushMin();
        if (rng.nextDouble() < cfg.pushJamProb()) {
            pushJams++;
            events.add(new Event(m(pushStart), Event.Type.FAULT, p,
                    "отказ выталкивания (тугой ход), простой КВ %.0f мин".formatted(cfg.pushJamRepairMin())));
            pushDur += cfg.pushJamRepairMin();
        }
        double pushEnd = pushStart + pushDur;

        if (readyAt[p] > planned + 1e-9) {
            events.add(new Event(m(pushStart), Event.Type.LATE, p,
                    "печь не была готова к слоту, опоздание %.1f мин".formatted(readyAt[p] - planned)));
            lateCount++;
        }
        if (pushStart - readyAt[p] > cfg.toleranceMin() + 1e-9) {
            events.add(new Event(m(pushStart), Event.Type.OVERHOLD, p,
                    "передержка кокса %.0f мин (допуск %.0f)"
                            .formatted(pushStart - readyAt[p], cfg.toleranceMin())));
            overholdCount++;
        }

        events.add(new Event(m(pushStart), Event.Type.DISCHARGE, p,
                "выдача: слот %.1f, факт %.1f, выталкивание %.1f мин"
                        .formatted(planned, pushStart, pushDur)));
        discharges++;

        // ДСМ снимает дверь до выталкивания.
        double doorArrive = doorMachine.readyAt(p, pitch);
        double doorOffEnd = doorArrive + cfg.doorRemoveMin();
        doorMachine.addBusy(doorMachine.kind.travelMin(doorMachine.pos(), p, pitch)
                + doorMachine.kind.setupMin + cfg.doorRemoveMin());
        doorMachine.commit(p, doorOffEnd);

        // КВ выталкивает пирог и остаётся у печи.
        pusher.addBusy(pusher.kind.travelMin(pusher.pos(), p, pitch)
                + pusher.kind.setupMin + pushDur);
        pusher.commit(p, pushEnd);

        // ТВ принимает пирог и едет к тушильной башне, затем разгружается.
        double tower = towerPos();
        double tvToTower = quenchCar.kind.travelMin(p, tower, pitch);
        double tvFree = pushEnd + cfg.takeMin() + tvToTower + cfg.unloadMin();
        quenchCar.addBusy(quenchCar.kind.travelMin(quenchCar.pos(), p, pitch)
                + quenchCar.kind.setupMin + cfg.takeMin() + tvToTower + cfg.unloadMin());
        quenchCar.commit(tower, tvFree);

        // УВ берёт шихту у угольной башни и загружает камеру.
        double uvToTower = loadingCar.kind.travelMin(loadingCar.pos(), tower, pitch);
        double uvToOven = loadingCar.kind.travelMin(tower, p, pitch);
        double loadStart = Math.max(pushEnd,
                loadingCar.freeAt() + uvToTower + loadingCar.kind.setupMin
                        + uvToOven + loadingCar.kind.setupMin);
        double loadEnd = loadStart + cfg.loadMin();
        loadingCar.addBusy(uvToTower + uvToOven
                + 2 * loadingCar.kind.setupMin + cfg.loadMin());
        loadingCar.commit(p, loadEnd);

        // ДСМ возвращается и ставит дверь после загрузки.
        double doorOnStart = Math.max(loadEnd, doorMachine.readyAt(p, pitch));
        double doorOnEnd = doorOnStart + cfg.doorInstallMin();
        doorMachine.addBusy(doorMachine.kind.setupMin + cfg.doorInstallMin());
        doorMachine.commit(p, doorOnEnd);

        // Печь: коксование начинается с конца загрузки.
        dischargeAt[p] = pushStart;
        loadDoneAt[p] = loadEnd;
        readyAt[p] = loadEnd + cfg.cokingMin();

        events.add(new Event(m(loadEnd), Event.Type.LOADED, p,
                "камера загружена, коксование %.0f мин".formatted(cfg.cokingMin())));
        nextIssue++;
    }

    // --- отказы обогрева ---

    private void applyHeatFault(int p, double t) {
        ovenFaults++;
        heatRecoverAt[p] = t + cfg.ovenFaultRepairMin();
        // Печь с отказом обогрева коксование не идёт: готовность сдвигается.
        readyAt[p] = Math.max(readyAt[p], heatRecoverAt[p]);
        nextHeatFaultAt[p] = sampleHeatFault(t);
        events.add(new Event(m(t), Event.Type.FAULT, p,
                "отказ обогрева камеры, печь в простое %.0f мин".formatted(cfg.ovenFaultRepairMin())));
    }

    /** Экспоненциальное время до следующего отказа обогрева. */
    private double sampleHeatFault(double from) {
        double hours = -Math.log(1 - rng.nextDouble()) / cfg.ovenFaultPerHour();
        return from + hours * 60;
    }

    // --- служебные поиски ближайших событий ---

    private int earliestHeatFault() {
        int p = -1; double best = Double.MAX_VALUE;
        for (int i = 1; i <= cfg.ovenCount(); i++)
            if (heatRecoverAt[i] <= simMin && nextHeatFaultAt[i] < best) { best = nextHeatFaultAt[i]; p = i; }
        return p;
    }

    private int earliestRecovery() {
        int p = -1; double best = Double.MAX_VALUE;
        for (int i = 1; i <= cfg.ovenCount(); i++)
            if (heatRecoverAt[i] > simMin && heatRecoverAt[i] < best) { best = heatRecoverAt[i]; p = i; }
        return p;
    }

    private long m(double t) { return (long) Math.ceil(t - 1e-9); }

    // --- состояние и доступ ---

    /** Состояние печи на текущий момент симуляции. */
    public OvenState state(int ovenNo) {
        if (ovenNo < 1 || ovenNo > cfg.ovenCount())
            throw new IllegalArgumentException("печь %d вне диапазона 1..%d".formatted(ovenNo, cfg.ovenCount()));
        double t = simMin;
        if (t < heatRecoverAt[ovenNo]) return OvenState.FAULT;
        if (dischargeAt[ovenNo] <= t && t < loadDoneAt[ovenNo]) return OvenState.EMPTY;
        if (t < readyAt[ovenNo]) return OvenState.COKING;
        return (t - readyAt[ovenNo] > cfg.toleranceMin()) ? OvenState.OVERHOLD : OvenState.READY;
    }

    public long simMinutes() { return simMin; }

    public List<Event> events() { return List.copyOf(events); }

    /** Живые объекты машин комплекта (для расчётов и будущего API). */
    public List<Machine> machines() {
        return List.of(doorMachine, pusher, quenchCar, loadingCar);
    }

    /** Срез состояния машин для экспорта в график/карту. */
    public List<MachineStatus> machineStatus() {
        return machines().stream().map(this::statusOf).toList();
    }

    private MachineStatus statusOf(Machine mch) {
        String where = mch.pos() > cfg.ovenCount() + 1e-9
                ? switch (mch.kind) {
                    case QUENCH_CAR -> "тушильная башня";
                    case LOADING_CAR -> "угольная башня";
                    default -> "за батареей";
                }
                : "печь %.0f".formatted(mch.pos());
        return new MachineStatus(mch.name, mch.kind.code, where, mch.pos(),
                (mch.pos() - 1) * cfg.pitchM(), mch.freeAt(), mch.tasksDone(), util(mch));
    }

    /** Утилизация машины: доля времени в переездах и операциях. */
    private double util(Machine mch) {
        return simMin == 0 ? 0 : Math.min(1.0, mch.busyMin() / simMin);
    }

    /** Срез состояния машины. */
    public record MachineStatus(String name, String code, String where, double pos, double posM,
                                double freeAt, int tasksDone, double utilization) {
        @Override
        public String toString() {
            return "%s (%s): %s, %.1f м, свободна с %.1f мин, задач %d, занятость %.0f%%"
                    .formatted(name, code, where, posM, freeAt, tasksDone, utilization * 100);
        }
    }

    public int discharges() { return discharges; }
    public int lateCount() { return lateCount; }
    public int overholdCount() { return overholdCount; }
    public int pushJams() { return pushJams; }
    public int ovenFaults() { return ovenFaults; }

    /** Средний фактический интервал выдач, мин. Норматив Δt = cycle/N. */
    public double avgIntervalMin() {
        return discharges == 0 ? Double.NaN : (double) simMin / discharges;
    }
}
