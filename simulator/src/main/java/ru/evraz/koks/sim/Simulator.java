package ru.evraz.koks.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Симулятор коксовой батареи v0.1 — событийное ядро в памяти с машинами
 * как отдельными сущностями.
 *
 * <p>Модель:
 * <ul>
 *   <li>выдачи следуют в порядке, заданном серийностью (docs/05 §2), — это жёсткое
 *       ограничение; времена задаёт план ({@link #setPlan}), без плана — базовая сетка
 *       k·Δt, Δt = T_цикл / N;</li>
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
 *   <li>отказ обогрева камеры: печь в FAULT до ремонта. Если отказ случился
 *       в середине коксования, нагрев стоит весь ремонт — готовность
 *       сдвигается на длительность ремонта; если печь уже готова,
 *       выдача невозможна до конца ремонта.</li>
 * </ul>
 * Машины — узкое горлышко: если машина занята, следующая выдача/загрузка
 * ждёт её, печи получают передержку.
 * Все времена — в минутах симуляции. Симуляция детерминирована по seed.
 *
 * <p>Состояние батареи целиком выгружается в снимок ({@link #exportState()}) и
 * принимается обратно ({@link #restore(BatteryState)}): по снимку симуляция
 * продолжается ровно с того же места, с тем же потоком случайных чисел.
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
    /** Подтверждённые выдачи: фактическое и плановое время для аналитики. */
    private final List<DischargeRecord> dischargeHistory = new ArrayList<>();
    /** Номер печи в последней выдаче — нужен для пути тушильного вагона. */
    private int lastDischargeOven = 0;

    private long simMin = 0;
    private int nextIssue = 0;
    /** Сколько случайных чисел взято с создания симулятора — для восстановления потока по снимку. */
    private long randomDraws;

    private int discharges, lateCount, overholdCount, pushJams, ovenFaults;

    /** Принудительный отказ, запрошенный из UI/API (кнопка «Сгенерировать отказ»). */
    private boolean forcedPushJam;

    /** План выдач: плановое время начала выталкивания для выдач, начиная с индекса planStart. */
    private double[] plan = new double[0];
    private int planStart;
    /** Печь выведена оператором (ремонт, вне серии): её выдачи пропускаются до возврата. */
    private final boolean[] ovenUnavailable;
    /** Слотов, пропущенных из-за недоступных печей. */
    private int skipped;
    /** Возмущения: отказы, простои машин, вывод и возврат оборудования. Изменение — повод пересчитать план. */
    private long revision;

    /** «Бесконечность» для простоя до возврата: конечное число, чтобы арифметика не ломалась. */
    public static final double FAR = 1e12;

    public enum FailureType {
        /** Тугой ход при ближайшем выталкивании. */
        PUSH_JAM,
        /** Отказ обогрева ближайшей по потоку печи. */
        OVEN_HEATING
    }

    public Simulator(SimConfig cfg) {
        this(cfg, true);
    }

    /** Выделяет состояние; при {@code stationary == false} стартовые значения не выставляются. */
    private Simulator(SimConfig cfg, boolean stationary) {
        this.cfg = Objects.requireNonNull(cfg, "config");
        this.order = Seriality.dischargeOrder(cfg.ovenCount(), Seriality.Scheme.parse(cfg.seriality()));
        this.deltaMin = cfg.cycleMin() / cfg.ovenCount();
        this.rng = new Random(cfg.seed());
        this.readyAt = new double[cfg.ovenCount() + 1];
        this.heatRecoverAt = new double[cfg.ovenCount() + 1];
        this.nextHeatFaultAt = new double[cfg.ovenCount() + 1];
        this.dischargeAt = new double[cfg.ovenCount() + 1];
        this.loadDoneAt = new double[cfg.ovenCount() + 1];
        this.ovenUnavailable = new boolean[cfg.ovenCount() + 1];

        double tower = towerPos();
        this.doorMachine = new Machine(MachineKind.DOOR_MACHINE, "Двересъёмная машина", 1);
        this.pusher = new Machine(MachineKind.PUSHING, "Коксовыталкиватель", 1);
        this.quenchCar = new Machine(MachineKind.QUENCH_CAR, "Тушильный вагон", tower);
        this.loadingCar = new Machine(MachineKind.LOADING_CAR, "Углезагрузочный вагон", tower);

        if (stationary) initStationary();
    }

    /** Батарея в стационаре. Печь на позиции k выдачного потока готова к слоту
     *  с запасом slack = цикл − коксование − работы машин после выдачи
     *  (выталкивание + загрузка). */
    private void initStationary() {
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
            double planned = plannedSlot(nextIssue);

            // Выдача не раньше плана, готовности печи и готовности машин
            // (они могут быть заняты предыдущими задачами или стоять в простое).
            double tDischarge = pushStartFor(p, planned);

            int heatP = earliestHeatFault();
            double tHeat = heatP < 0 ? Double.MAX_VALUE : nextHeatFaultAt[heatP];
            int recP = earliestRecovery();
            double tRec = recP < 0 ? Double.MAX_VALUE : heatRecoverAt[recP];
            // Печь выведена (ремонт, вне серии): её слот пропускается, остальные идут в свои времена.
            double tSkip = ovenUnavailable[p] ? Math.max(planned, simMin) : Double.MAX_VALUE;

            double tNext = Math.min(tDischarge, Math.min(tHeat, Math.min(tRec, tSkip)));
            if (tNext > target) break;

            if (tRec <= tDischarge && tRec <= tHeat && tRec <= tSkip) {
                events.add(new Event(m(tRec), Event.Type.RECOVERED, recP, "отказ устранён, печь в работе"));
                simMin = m(tRec);
            } else if (tHeat <= tDischarge && tHeat <= tSkip) {
                applyHeatFault(heatP, tHeat);
            } else if (tSkip <= tDischarge) {
                events.add(new Event(m(tSkip), Event.Type.SKIPPED, p,
                        "печь вне серии: выдача пропущена, кокс остаётся в камере"));
                skipped++;
                simMin = m(tSkip);
                nextIssue++;
            } else {
                doDischarge(p, planned);
            }
        }
        simMin = m(target);
    }

    // --- план как вход симулятора ---

    /**
     * План выдач — вход симулятора: плановое время начала выталкивания для
     * последовательных выдач, начиная с текущей. План задаёт только времена:
     * порядок печей не меняется (ограничение серийности H1). Печь выдаётся не раньше
     * плана, своей готовности и освобождения машин. За пределами плана выдачи идут
     * по базовой сетке k·Δt.
     *
     * @param plannedStartsMin плановые времена по порядку выдач, не убывают
     */
    public void setPlan(double[] plannedStartsMin) {
        double[] accepted = plannedStartsMin == null ? new double[0] : plannedStartsMin.clone();
        for (int i = 0; i < accepted.length; i++) {
            if (!Double.isFinite(accepted[i]))
                throw new IllegalArgumentException("план содержит нечисловое время в позиции %d".formatted(i));
            if (i > 0 && accepted[i] < accepted[i - 1] - 1e-9)
                throw new IllegalArgumentException(
                        "план должен идти по времени: выдача %d раньше предыдущей".formatted(i));
        }
        plan = accepted;
        planStart = nextIssue;
    }

    /** Плановое время выдачи с индексом issueIndex: из плана, дальше — базовая сетка k·Δt. */
    public double plannedSlot(int issueIndex) {
        int k = issueIndex - planStart;
        if (k >= 0 && k < plan.length) return plan[k];
        if (k >= plan.length && plan.length > 0) return plan[plan.length - 1] + (k - plan.length + 1) * deltaMin;
        return (issueIndex + 1) * deltaMin;
    }

    /** Индекс выдачи, с которого начинается установленный план. */
    public int planStart() { return planStart; }

    /** Копия плана: плановые времена выдач от planStart. */
    public double[] plan() { return plan.clone(); }

    /**
     * Готовность печей к выдаче по порядку, начиная с текущей, — вход планировщика:
     * план не может назначить выдачу раньше готовности печи.
     *
     * @param count сколько выдач вперёд
     */
    public double[] readinessAhead(int count) {
        int n = Math.max(0, count);
        double[] out = new double[n];
        for (int k = 0; k < n; k++) out[k] = readyAt[order[(nextIssue + k) % order.length]];
        return out;
    }

    /** Печи по порядку выдач, начиная с текущей. */
    public int[] ovensAhead(int count) {
        int n = Math.max(0, count);
        int[] out = new int[n];
        for (int k = 0; k < n; k++) out[k] = order[(nextIssue + k) % order.length];
        return out;
    }

    /**
     * Самое раннее физически возможное начало ближайших выдач — вход планировщика:
     * машины идут подряд, не ожидая плана, печь выдаётся не раньше своей готовности
     * и выхода из простоя. Текущий простой машин учитывается; будущие отказы
     * выталкивания — нет (оптимистичный предел). Живой прогон не затрагивается:
     * расчёт идёт на клонах комплекта. Слот выведенной печи машин не занимает.
     */
    public double[] earliestAhead(int count) {
        int n = Math.max(0, Math.min(count, order.length));
        List<Machine> kit = clonedKit();
        double[] ready = readyAt.clone();
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            int p = order[(nextIssue + k) % order.length];
            if (ovenUnavailable[p]) {
                out[k] = simMin;            // слот пропускается — цепочку машин не трогаем
                continue;
            }
            double pitch = cfg.pitchM();
            Machine dm = kit.get(0);
            Machine kv = kit.get(1);
            Machine tv = kit.get(2);
            double t = Math.max(ready[p], kv.readyAt(p, pitch));
            t = Math.max(t, tv.readyAt(p, pitch));
            t = Math.max(t, dm.readyAt(p, pitch) + cfg.doorRemoveMin());
            out[k] = Math.max(t, simMin);   // в прошлом начинать нельзя
            runKit(kit, ready, p, out[k], cfg.pushMin(), false);
        }
        return out;
    }

    /** Выдач до конца текущего оборота, включая ближайшую. */
    public int rotationRemaining() {
        return order.length - (nextIssue % order.length);
    }

    // --- доступность оборудования ---

    /**
     * Вывести печь из серии (ремонт) или вернуть её. Выведенная печь не выдаётся:
     * её слот пропускается, кокс остаётся в камере до следующего прохода.
     * Повторный вызов с тем же значением ничего не меняет.
     */
    public void setOvenAvailable(int ovenNo, boolean available, String reason) {
        checkOven(ovenNo);
        if (ovenUnavailable[ovenNo] == !available) return;
        ovenUnavailable[ovenNo] = !available;
        revision++;
        events.add(new Event(simMin, available ? Event.Type.INCLUDED : Event.Type.EXCLUDED, ovenNo,
                available ? "печь возвращена в серию" + suffix(reason)
                        : "печь выведена из серии" + suffix(reason)));
    }

    /** Печь выведена оператором (ремонт, вне серии). */
    public boolean ovenAvailable(int ovenNo) {
        checkOven(ovenNo);
        return !ovenUnavailable[ovenNo];
    }

    /**
     * Машина встаёт в простой: на minutes минут от текущего момента или до возврата
     * ({@code minutes <= 0}). Текущая задача не отменяется, новых машина не берёт.
     */
    public void setMachineDown(MachineKind kind, double minutes) {
        Machine machine = machine(kind);
        double until = minutes > 0 ? simMin + minutes : FAR;
        if (!machine.downAt(simMin) || until > machine.holdUntil()) revision++;
        machine.hold(until);
        events.add(new Event(simMin, Event.Type.MACHINE_DOWN, 0,
                "%s: простой %s".formatted(machine.name,
                        minutes > 0 ? "%.0f мин".formatted(minutes) : "до возврата в работу")));
    }

    /** Вернуть машину в работу. */
    public void setMachineUp(MachineKind kind) {
        Machine machine = machine(kind);
        if (!machine.downAt(simMin)) return;
        machine.release();
        revision++;
        events.add(new Event(simMin, Event.Type.MACHINE_UP, 0,
                "%s возвращена в работу".formatted(machine.name)));
    }

    /** В простое ли машина в момент nowMin. */
    public boolean machineDownAt(MachineKind kind, double nowMin) {
        return machine(kind).downAt(nowMin);
    }

    /** Возмущений с начала прогона: изменилось — план стоит пересчитать. */
    public long revision() { return revision; }

    public int skipped() { return skipped; }

    private static String suffix(String reason) {
        return reason == null || reason.isBlank() ? "" : ": " + reason.strip();
    }

    private Machine machine(MachineKind kind) {
        return switch (kind) {
            case DOOR_MACHINE -> doorMachine;
            case PUSHING -> pusher;
            case QUENCH_CAR -> quenchCar;
            case LOADING_CAR -> loadingCar;
        };
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
        double pushStart = pushStartFor(p, planned);

        // Отказ выталкивания: тугой ход, машина занята дольше.
        double pushDur = cfg.pushMin();
        boolean jam = nextRandom() < cfg.pushJamProb();
        if (forcedPushJam) {
            jam = true;
            forcedPushJam = false;
        }
        if (jam) {
            pushJams++;
            revision++;
            events.add(new Event(m(pushStart), Event.Type.FAULT, p,
                    "отказ выталкивания (тугой ход), простой КВ %.0f мин".formatted(cfg.pushJamRepairMin())));
            pushDur += cfg.pushJamRepairMin();
        }
        double pushEnd = pushStart + pushDur;

        if (readyAt[p] > planned + 1e-9) {
            events.add(new Event(m(pushStart), Event.Type.LATE, p,
                    "печь не была готова к плану, опоздание %.1f мин".formatted(readyAt[p] - planned)));
            lateCount++;
        }
        if (pushStart - readyAt[p] > cfg.toleranceMin() + 1e-9) {
            events.add(new Event(m(pushStart), Event.Type.OVERHOLD, p,
                    "передержка кокса %.0f мин (допуск %.0f)"
                            .formatted(pushStart - readyAt[p], cfg.toleranceMin())));
            overholdCount++;
        }

        events.add(new Event(m(pushStart), Event.Type.DISCHARGE, p,
                "выдача: план %.1f, факт %.1f, выталкивание %.1f мин"
                        .formatted(planned, pushStart, pushDur)));
        dischargeHistory.add(new DischargeRecord(p, planned, pushStart, readyAt[p]));
        lastDischargeOven = p;
        discharges++;

        double loadEnd = runKit(machines(), readyAt, p, pushStart, pushDur, true);
        dischargeAt[p] = pushStart;
        loadDoneAt[p] = loadEnd;

        events.add(new Event(m(loadEnd), Event.Type.LOADED, p,
                "камера загружена, коксование %.0f мин".formatted(cfg.cokingMin())));
        nextIssue++;
    }

    /**
     * Задачи комплекта машин на выдачу печи p, начавшуюся в pushStart: ДСМ снимает
     * и ставит дверь, КВ выталкивает пирог, ТВ везёт его к башне, УВ загружает камеру.
     * Порядок коммитов и формулы — один в один жизненный цикл выдачи; метод работает
     * и с клонами комплекта ({@link #earliestAhead(int)}), поэтому физика планировщика
     * и симулятора не расходится.
     *
     * @param trackWork учитывать работу в статистике занятости (для клонов не нужно)
     * @return момент окончания загрузки камеры
     */
    private double runKit(List<Machine> kit, double[] ready, int p, double pushStart,
                          double pushDur, boolean trackWork) {
        double pitch = cfg.pitchM();
        Machine dm = kit.get(0);
        Machine kv = kit.get(1);
        Machine tv = kit.get(2);
        Machine uv = kit.get(3);
        double pushEnd = pushStart + pushDur;

        // ДСМ снимает дверь до выталкивания.
        double doorArrive = dm.readyAt(p, pitch);
        double doorOffEnd = doorArrive + cfg.doorRemoveMin();
        double doorApproach = dm.kind.travelMin(dm.pos(), p, pitch) + dm.kind.setupMin;
        if (trackWork) {
            dm.addBusy(doorApproach + cfg.doorRemoveMin());
            dm.addBusyPeriod(doorArrive - doorApproach, doorOffEnd);
        }
        dm.commit(p, doorOffEnd);

        // КВ выталкивает пирог и остаётся у печи.
        double pushApproach = kv.kind.travelMin(kv.pos(), p, pitch) + kv.kind.setupMin;
        if (trackWork) {
            kv.addBusy(pushApproach + pushDur);
            kv.addBusyPeriod(pushStart - pushApproach, pushEnd);
        }
        kv.commit(p, pushEnd);

        // ТВ принимает пирог и едет к тушильной башне, затем разгружается.
        double tower = towerPos();
        double tvToTower = tv.kind.travelMin(p, tower, pitch);
        double tvFree = pushEnd + cfg.takeMin() + tvToTower + cfg.unloadMin();
        double tvApproach = tv.kind.travelMin(tv.pos(), p, pitch) + tv.kind.setupMin;
        if (trackWork) {
            // Вагон занят с выезда к печи и до разгрузки у башни: в окно
            // [pushStart, pushEnd] он стоит под печью и принимает кокс.
            tv.addBusy(tvApproach + cfg.pushMin() + cfg.takeMin() + tvToTower + cfg.unloadMin());
            tv.addBusyPeriod(pushStart - tvApproach, pushEnd);
            tv.addBusyPeriod(pushEnd, tvFree);
        }
        tv.commit(tower, tvFree);

        // УВ берёт шихту у угольной башни и загружает камеру (не раньше конца своего простоя).
        double uvToTower = uv.kind.travelMin(uv.pos(), tower, pitch);
        double uvToOven = uv.kind.travelMin(tower, p, pitch);
        double loadStart = Math.max(pushEnd,
                uv.availableAt() + uvToTower + uv.kind.setupMin
                        + uvToOven + uv.kind.setupMin);
        double loadEnd = loadStart + cfg.loadMin();
        double uvApproach = uvToTower + uvToOven + 2 * uv.kind.setupMin;
        if (trackWork) {
            uv.addBusy(uvApproach + cfg.loadMin());
            uv.addBusyPeriod(loadStart - uvApproach, loadEnd);
        }
        uv.commit(p, loadEnd);

        // ДСМ возвращается и ставит дверь после загрузки.
        double doorOnStart = Math.max(loadEnd, dm.readyAt(p, pitch));
        double doorOnEnd = doorOnStart + cfg.doorInstallMin();
        if (trackWork) {
            dm.addBusy(dm.kind.setupMin + cfg.doorInstallMin());
            dm.addBusyPeriod(doorOnStart, doorOnEnd);
        }
        dm.commit(p, doorOnEnd);

        // Печь: коксование начинается с конца загрузки.
        ready[p] = loadEnd + cfg.cokingMin();
        return loadEnd;
    }

    /** Копия комплекта машин для расчётов без воздействия на живой прогон. */
    private List<Machine> clonedKit() {
        List<Machine> clones = new ArrayList<>(4);
        for (Machine mch : machines()) {
            Machine clone = new Machine(mch.kind, mch.name, mch.pos());
            clone.restoreState(mch.exportState());
            clones.add(clone);
        }
        return clones;
    }

    // --- отказы обогрева ---

    private void applyHeatFault(int p, double t) {
        if (t < heatRecoverAt[p] - 1e-9) {
            // Отказ назначен внутри уже идущего простоя — это тот же простой,
            // а не второй: не считаем и не сдвигаем готовность повторно.
            nextHeatFaultAt[p] = sampleHeatFault(heatRecoverAt[p]);
            return;
        }
        ovenFaults++;
        revision++;
        heatRecoverAt[p] = t + cfg.ovenFaultRepairMin();
        if (t < readyAt[p]) {
            // Отказ в середине коксования: нагрев стоит, пока идёт ремонт, —
            // готовность уезжает ровно на длительность ремонта.
            readyAt[p] += cfg.ovenFaultRepairMin();
        } else {
            // Печь уже готова и перестаивает: выдача невозможна до конца ремонта.
            readyAt[p] = Math.max(readyAt[p], heatRecoverAt[p]);
        }
        nextHeatFaultAt[p] = sampleHeatFault(t);
        events.add(new Event(m(t), Event.Type.FAULT, p,
                "отказ обогрева камеры, печь в простое %.0f мин".formatted(cfg.ovenFaultRepairMin())));
    }

    /** Экспоненциальное время до следующего отказа обогрева. */
    private double sampleHeatFault(double from) {
        double hours = -Math.log(1 - nextRandom()) / cfg.ovenFaultPerHour();
        if (!Double.isFinite(hours))
            return from + FAR;                  // отказы выключены: следующий отказ за горизонтом
        return from + hours * 60;
    }

    /** Единственная точка, где берётся случайное число: счётчик нужен, чтобы
     *  восстановить поток генератора из снимка состояния. */
    private double nextRandom() {
        randomDraws++;
        return rng.nextDouble();
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

    // --- сохранение и восстановление состояния ---

    /** Версия формата снимка: меняется при несовместимых изменениях полей. */
    private static final int STATE_FORMAT_VERSION = 2;

    /**
     * Снимок батареи целиком: то, что уходит в файл при выгрузке и приходит
     * обратно при загрузке. Все времена — минуты симуляции.
     *
     * @param formatVersion     версия формата, сейчас 1
     * @param config            конфигурация, с которой шла симуляция
     * @param simMin            модельное время снимка
     * @param nextIssue         индекс следующей выдачи в порядке обхода
     * @param lastDischargeOven печь последней выдачи, 0 — выдач не было
     * @param discharges        выполнено выдач
     * @param lateCount         выдач с неготовой к слоту печью
     * @param overholdCount     выдач с передержкой сверх допуска
     * @param pushJams          тугих ходов
     * @param ovenFaults        отказов обогрева
     * @param forcedPushJam     ждёт принудительный тугой ход
     * @param randomDraws       сколько случайных чисел взято к моменту снимка
     * @param planStart         индекс выдачи, с которого начинается план
     * @param planStartsMin     план: плановое время выдач от planStart
     * @param ovenUnavailable   печи, выведенные оператором (индекс — номер печи, [0] не используется)
     * @param skipped           слотов, пропущенных из-за выведенных печей
     * @param revision          счётчик возмущений на момент снимка
     * @param ovens             печи по порядку номеров
     * @param machines          машины комплекта: ДСМ, КВ, ТВ, УВ
     * @param events            журнал событий в порядке добавления
     * @param dischargeHistory  фактические выдачи, от ранней к поздней
     */
    public record BatteryState(
            int formatVersion,
            SimConfig config,
            long simMin,
            int nextIssue,
            int lastDischargeOven,
            int discharges,
            int lateCount,
            int overholdCount,
            int pushJams,
            int ovenFaults,
            boolean forcedPushJam,
            long randomDraws,
            int planStart,
            double[] planStartsMin,
            boolean[] ovenUnavailable,
            int skipped,
            long revision,
            List<OvenSnapshot> ovens,
            List<Machine.Snapshot> machines,
            List<Event> events,
            List<DischargeRecord> dischargeHistory) {

        /** Тот же снимок с другой конфигурацией — например, для офлайн-реплея
         *  со случайными отказами, выключенными на время сравнения алгоритмов. */
        public BatteryState withConfig(SimConfig replacement) {
            return new BatteryState(formatVersion, replacement, simMin, nextIssue,
                    lastDischargeOven, discharges, lateCount, overholdCount, pushJams,
                    ovenFaults, forcedPushJam, randomDraws, planStart, planStartsMin,
                    ovenUnavailable, skipped, revision, ovens, machines, events, dischargeHistory);
        }
    }

    /**
     * Печь в снимке.
     *
     * @param state             состояние на момент снимка — справочное: при загрузке
     *                          пересчитывается по временам, правка этого поля ничего не меняет
     * @param readyAtMin        момент готовности к выдаче
     * @param heatRecoverAtMin  до этого момента печь в FAULT по обогреву
     * @param nextHeatFaultAtMin запланированный момент следующего отказа обогрева
     * @param dischargeAtMin    факт последней выдачи (начало выталкивания)
     * @param loadDoneAtMin     факт окончания последней загрузки
     */
    public record OvenSnapshot(int number, String state, double readyAtMin, double heatRecoverAtMin,
                               double nextHeatFaultAtMin, double dischargeAtMin, double loadDoneAtMin) { }

    /** Снимок текущего состояния — копия: дальнейшая работа симулятора её не меняет. */
    public BatteryState exportState() {
        List<OvenSnapshot> ovens = new ArrayList<>(cfg.ovenCount());
        for (int p = 1; p <= cfg.ovenCount(); p++) {
            ovens.add(new OvenSnapshot(p, state(p).name(), readyAt[p], heatRecoverAt[p],
                    nextHeatFaultAt[p], dischargeAt[p], loadDoneAt[p]));
        }
        return new BatteryState(STATE_FORMAT_VERSION, cfg, simMin, nextIssue, lastDischargeOven,
                discharges, lateCount, overholdCount, pushJams, ovenFaults, forcedPushJam,
                randomDraws, planStart, plan.clone(), ovenUnavailable.clone(), skipped, revision,
                List.copyOf(ovens),
                machines().stream().map(Machine::exportState).toList(),
                List.copyOf(events), List.copyOf(dischargeHistory));
    }

    /**
     * Восстановить симулятор из снимка. Продолжение совпадает с тем, что было бы
     * у исходного симулятора, включая случайные отказы. Снимок с другим числом
     * печей или машин, с неверной версией формата или с нечисловым временем
     * отвергается целиком.
     */
    public static Simulator restore(BatteryState state) {
        if (state == null)
            throw new IllegalArgumentException("снимок состояния пуст");
        if (state.formatVersion() != STATE_FORMAT_VERSION)
            throw new IllegalArgumentException("версия формата снимка %d, поддерживается %d"
                    .formatted(state.formatVersion(), STATE_FORMAT_VERSION));
        SimConfig cfg = state.config();
        if (cfg == null)
            throw new IllegalArgumentException("в снимке нет конфигурации батареи");
        if (state.simMin() < 0 || state.nextIssue() < 0 || state.randomDraws() < 0)
            throw new IllegalArgumentException("время, индекс выдачи и счётчик случайных чисел не могут быть отрицательными");

        Simulator sim = new Simulator(cfg, false);

        List<OvenSnapshot> ovens = state.ovens();
        if (ovens == null || ovens.size() != cfg.ovenCount())
            throw new IllegalArgumentException("в снимке %s печей, а в конфигурации батареи %d"
                    .formatted(ovens == null ? "нет" : String.valueOf(ovens.size()), cfg.ovenCount()));
        for (int p = 1; p <= cfg.ovenCount(); p++) {
            OvenSnapshot o = ovens.get(p - 1);
            if (o == null || o.number() != p)
                throw new IllegalArgumentException("печь №%d в снимке отсутствует или идёт не по порядку".formatted(p));
            sim.readyAt[p] = finite(o.readyAtMin(), "readyAtMin", p);
            sim.heatRecoverAt[p] = finite(o.heatRecoverAtMin(), "heatRecoverAtMin", p);
            sim.nextHeatFaultAt[p] = finite(o.nextHeatFaultAtMin(), "nextHeatFaultAtMin", p);
            sim.dischargeAt[p] = finite(o.dischargeAtMin(), "dischargeAtMin", p);
            sim.loadDoneAt[p] = finite(o.loadDoneAtMin(), "loadDoneAtMin", p);
        }

        List<Machine.Snapshot> machines = state.machines();
        List<Machine> kit = sim.machines();
        if (machines == null || machines.size() != kit.size())
            throw new IllegalArgumentException("в снимке %s машин, в комплекте %d"
                    .formatted(machines == null ? "нет" : String.valueOf(machines.size()), kit.size()));
        for (int i = 0; i < kit.size(); i++) kit.get(i).restoreState(machines.get(i));

        sim.simMin = state.simMin();
        sim.nextIssue = state.nextIssue();
        sim.lastDischargeOven = state.lastDischargeOven();
        sim.discharges = state.discharges();
        sim.lateCount = state.lateCount();
        sim.overholdCount = state.overholdCount();
        sim.pushJams = state.pushJams();
        sim.ovenFaults = state.ovenFaults();
        sim.forcedPushJam = state.forcedPushJam();
        sim.skipped = state.skipped();
        sim.revision = state.revision();

        sim.planStart = state.planStart();
        sim.plan = state.planStartsMin() == null ? new double[0] : state.planStartsMin().clone();
        for (double t : sim.plan) {
            if (!Double.isFinite(t))
                throw new IllegalArgumentException("в плане выдачи есть нечисловое время");
        }
        boolean[] withdrawn = state.ovenUnavailable();
        for (int p = 1; p <= cfg.ovenCount(); p++)
            sim.ovenUnavailable[p] = withdrawn != null && p < withdrawn.length && withdrawn[p];

        sim.events.addAll(withoutNulls(state.events(), "events"));
        sim.dischargeHistory.addAll(withoutNulls(state.dischargeHistory(), "dischargeHistory"));

        // Поток случайных чисел восстанавливается без смены генератора: тот же seed
        // и столько же вызовов, сколько было к моменту снимка.
        sim.randomDraws = state.randomDraws();
        for (long i = 0; i < state.randomDraws(); i++) sim.rng.nextDouble();
        return sim;
    }

    private static double finite(double value, String field, int ovenNo) {
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("у печи №%d поле %s — не число".formatted(ovenNo, field));
        return value;
    }

    private static <T> List<T> withoutNulls(List<T> source, String field) {
        if (source == null) return List.of();
        for (T item : source)
            if (item == null)
                throw new IllegalArgumentException("в списке " + field + " есть пустой элемент");
        return source;
    }

    // --- состояние и доступ ---

    /** Состояние печи на текущий момент симуляции. */
    public OvenState state(int ovenNo) {
        checkOven(ovenNo);
        double t = simMin;
        if (ovenUnavailable[ovenNo]) return OvenState.UNAVAILABLE;
        if (t < heatRecoverAt[ovenNo]) return OvenState.FAULT;
        if (dischargeAt[ovenNo] <= t && t < loadDoneAt[ovenNo]) return OvenState.EMPTY;
        if (t < readyAt[ovenNo]) return OvenState.COKING;
        return (t - readyAt[ovenNo] > cfg.toleranceMin()) ? OvenState.OVERHOLD : OvenState.READY;
    }

    private void checkOven(int ovenNo) {
        if (ovenNo < 1 || ovenNo > cfg.ovenCount())
            throw new IllegalArgumentException("печь %d вне диапазона 1..%d".formatted(ovenNo, cfg.ovenCount()));
    }

    public long simMinutes() { return simMin; }

    /** Конфигурация, с которой создан симулятор. */
    public SimConfig config() { return cfg; }

    public int ovenCount() { return cfg.ovenCount(); }

    /** Нормативный шаг выдачи Δt = T_цикл / N, мин. */
    public double deltaMin() { return deltaMin; }

    /** Индекс следующей выдачи в порядке обхода. */
    public int nextIssue() { return nextIssue; }

    /** Копия порядка выдачи за оборот (номера печей). */
    public int[] order() { return order.clone(); }

    /** Момент готовности печи к выдаче, мин симуляции. */
    public double readyAt(int ovenNo) { checkOven(ovenNo); return readyAt[ovenNo]; }

    /** Момент окончания загрузки камеры, мин симуляции. */
    public double loadDoneAt(int ovenNo) { checkOven(ovenNo); return loadDoneAt[ovenNo]; }

    /** Факт начала последней выдачи, мин симуляции. */
    public double dischargeAt(int ovenNo) { checkOven(ovenNo); return dischargeAt[ovenNo]; }

    /**
     * Прогноз ближайших выдач: печь, плановый слот, готовность и оценка начала.
     * Для ближайшей выдачи учитывается текущая занятость машин; для следующих —
     * только слот и готовность печи (без учёта будущих простоев).
     *
     * @param limit сколько выдач вернуть, не больше числа печей в обороте
     */
    public List<PlannedDischarge> forecast(int limit) {
        int n = Math.max(0, Math.min(limit, order.length));
        List<PlannedDischarge> out = new ArrayList<>(n);
        // Времена берутся по индексу выдачи, а не по позиции в списке: пропуск
        // выведенной печи не сдвигает слоты остальных.
        for (int k = 0; out.size() < n && k < order.length; k++) {
            int idx = nextIssue + k;
            int p = order[idx % order.length];
            if (ovenUnavailable[p]) continue;
            double planned = plannedSlot(idx);
            double start = out.isEmpty() ? pushStartFor(p, planned) : Math.max(planned, readyAt[p]);
            out.add(new PlannedDischarge(p, planned, readyAt[p], start));
        }
        return out;
    }

    /** Позиция прогноза выдачи. Все времена — минуты симуляции. */
    public record PlannedDischarge(int ovenNo, double plannedMin, double readyAtMin, double predictedStartMin) { }

    /**
     * Принудительный отказ для кнопки «Сгенерировать отказ» в UI.
     * PUSH_JAM сработает на ближайшей выдаче, OVEN_HEATING — сразу на ближайшей
     * по потоку печи.
     */
    public void injectFailure(FailureType type) {
        switch (type) {
            case PUSH_JAM -> forcedPushJam = true;
            case OVEN_HEATING -> injectOvenHeatingFault(order[nextIssue % order.length]);
        }
    }

    /** Принудительный отказ обогрева конкретной печи в текущий момент симуляции. */
    public void injectOvenHeatingFault(int ovenNo) {
        checkOven(ovenNo);
        applyHeatFault(ovenNo, simMin);
    }

    public List<Event> events() {
        // События могут добавляться с упреждением (LOADED — на конец загрузки),
        // поэтому наружу отдаём хронологический порядок; при равном времени
        // сохраняется порядок добавления.
        return events.stream()
                .sorted(java.util.Comparator.comparingLong(Event::simMin))
                .toList();
    }

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
                (mch.pos() - 1) * cfg.pitchM(), mch.availableAt(), mch.tasksDone(), util(mch),
                mch.downAt(simMin), mch.holdUntil());
    }

    /** Утилизация машины: доля времени в переездах и операциях. */
    private double util(Machine mch) {
        return simMin == 0 ? 0 : Math.min(1.0, mch.busyMinutesUntil(simMin) / simMin);
    }

    /** Занятость машины в окне [fromMin, toMin]: переезды и операции, мин. */
    public double machineBusyMin(MachineKind kind, double fromMin, double toMin) {
        Machine mch = machine(kind);
        return Math.max(0, mch.busyMinutesUntil(toMin) - mch.busyMinutesUntil(fromMin));
    }

    /** Срез состояния машины. */
    public record MachineStatus(String name, String code, String where, double pos, double posM,
                                double freeAt, int tasksDone, double utilization,
                                boolean down, double downUntil) {
        /** Занята ли машина в момент nowMin (running/idle в API). */
        public boolean runningAt(double nowMin) { return freeAt > nowMin + 1e-9; }

        @Override
        public String toString() {
            return "%s (%s): %s, %.1f м, свободна с %.1f мин, задач %d, занятость %.0f%%%s"
                    .formatted(name, code, where, posM, freeAt, tasksDone, utilization * 100,
                            down ? ", в простое" : "");
        }
    }

    /**
     * Фактическая выдача: печь, плановое время, факт начала выталкивания и готовность печи
     * на этот момент (разница факта и готовности — передержка кокса).
     */
    public record DischargeRecord(int ovenNo, double plannedMin, double actualMin, double readyAtMin) { }

    /** Фактическая история операций, от самой ранней до последней; без прогноза. */
    public List<DischargeRecord> dischargeHistory() {
        return List.copyOf(dischargeHistory);
    }

    public int lastDischargeOven() { return lastDischargeOven; }

    public int discharges() { return discharges; }
    public int lateCount() { return lateCount; }
    public int overholdCount() { return overholdCount; }
    public int pushJams() { return pushJams; }
    public int ovenFaults() { return ovenFaults; }

    /** Средний фактический интервал между завершёнными выдачами (не время / число выдач). */
    public double avgIntervalMin() {
        int n = dischargeHistory.size();
        if (n < 2) return Double.NaN;
        return (dischargeHistory.get(n - 1).actualMin()
                - dischargeHistory.get(0).actualMin()) / (n - 1);
    }
}
