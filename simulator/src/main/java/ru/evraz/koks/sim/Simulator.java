package ru.evraz.koks.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Базовый симулятор коксовой батареи v0 — чистое событийное ядро в памяти.
 *
 * Модель (упрощённо, без машин/телеметрии — следующий этап):
 * <ul>
 *   <li>печи выдаются в порядке, заданном серийностью (docs/05 §2),
 *       номинальный интервал Δt = T_цикл / N;</li>
 *   <li>после выдачи печь заряжается (chargeMin) и коксовится (cokingMin);
 *       печь должна быть готова к следующему слоту, в противном случае — LATE;</li>
 *   <li>если печь готова раньше слота и ждёт дольше toleranceMin — OVERHOLD;</li>
 *   <li>два вида отказов: выталкивание при выдаче (затягивает выдачный поток
 *       на repairMin) и отказ обогрева камеры (печь в FAULT, коксование
 *       сдвигается).</li>
 * </ul>
 * Все времена — в минутах симуляции. Симуляция детерминирована по seed.
 */
public final class Simulator {

    private final SimConfig cfg;
    private final int[] order;               // печи в порядке выдачи за оборот
    private final double deltaMin;           // Δt выдачи = cycle / N
    private final Random rng;

    // Состояние печей (индекс = номер печи 1..N, [0] не используется)
    private final double[] readyAt;          // момент готовности печи к выдаче
    private final double[] heatRecoverAt;    // до этого момента печь в FAULT по обогреву
    private final double[] nextHeatFaultAt;  // запланированный момент следующего отказа обогрева

    private final List<Event> events = new ArrayList<>();

    private long simMin = 0;      // текущее время симуляции
    private double busyUntil = 0; // до этого момента машина занята выдачей
    private int nextIssue = 0;    // порядковый номер следующей выдачи (0-based)

    private int discharges, lateCount, overholdCount, pushJams, ovenFaults;

    public Simulator(SimConfig cfg) {
        this.cfg = cfg;
        this.order = Seriality.dischargeOrder(cfg.ovenCount(), Seriality.Scheme.parse(cfg.seriality()));
        this.deltaMin = cfg.cycleMin() / cfg.ovenCount();
        this.rng = new Random(cfg.seed());
        this.readyAt = new double[cfg.ovenCount() + 1];
        this.heatRecoverAt = new double[cfg.ovenCount() + 1];
        this.nextHeatFaultAt = new double[cfg.ovenCount() + 1];

        // Инициализация: батарея уже в работе и находится в стационаре.
        // Печь на позиции k выдачного потока готова к своему слоту
        // наперёд как раз на "slack" — тот же запас, который повторяется
        // в следующих оборотах (cycle − загрузка − коксование).
        double slack = Math.max(0, cfg.cycleMin() - cfg.chargeMin() - cfg.cokingMin());
        for (int k = 0; k < order.length; k++) {
            int p = order[k];
            readyAt[p] = (k + 1) * deltaMin - slack;
            nextHeatFaultAt[p] = sampleHeatFault(0);
        }
    }

    /** Прогнать симуляцию вперёд на minutes минут симуляции. */
    public void advance(long minutes) {
        double target = simMin + minutes;
        while (true) {
            // Ближайший момент выдачи: слот, но не раньше готовности печи
            // и не раньше, чем машина освободится от предыдущей выдачи.
            int p = order[nextIssue % order.length];
            double planned = (nextIssue + 1) * deltaMin;
            double tDischarge = Math.max(Math.max(planned, readyAt[p]), busyUntil);

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
                doDischarge(p, planned, tDischarge);
            }
        }
        simMin = m(target);
    }

    // --- выдача ---

    private void doDischarge(int p, double planned, double fact) {
        String pause = "";
        if (readyAt[p] > planned + 1e-9) {
            events.add(new Event(m(fact), Event.Type.LATE, p,
                    "печь не была готова к слоту, опоздание %.1f мин".formatted(readyAt[p] - planned)));
            lateCount++;
        }

        if (rng.nextDouble() < cfg.pushJamProb()) {
            pushJams++;
            events.add(new Event(m(fact), Event.Type.FAULT, p, "отказ выталкивания (тугой ход), ремонт %.0f мин"
                    .formatted(cfg.pushJamRepairMin())));
            fact += cfg.pushJamRepairMin();
            pause = "после ремонта ";
        }

        if (fact - readyAt[p] > cfg.toleranceMin() + 1e-9) {
            events.add(new Event(m(fact), Event.Type.OVERHOLD, p,
                    "%sпередержка кокса %.0f мин (допуск %.0f)"
                            .formatted(pause, fact - readyAt[p], cfg.toleranceMin())));
            overholdCount++;
        }

        events.add(new Event(m(fact), Event.Type.DISCHARGE, p, "выдача: слот %.1f, факт %.1f"
                .formatted(planned, fact)));
        discharges++;

        // После выдачи печь сразу заряжается и коксовится до следующего слота.
        readyAt[p] = fact + cfg.chargeMin() + cfg.cokingMin();
        busyUntil = fact + cfg.dischargeMin();
        nextIssue++;
    }

    // --- отказы обогрева ---

    private void applyHeatFault(int p, double t) {
        ovenFaults++;
        heatRecoverAt[p] = t + cfg.ovenFaultRepairMin();
        // Печь сFaultным обогревом коксование не идёт: готовность сдвигается.
        readyAt[p] = Math.max(readyAt[p], heatRecoverAt[p]);
        nextHeatFaultAt[p] = sampleHeatFault(t);
        events.add(new Event(m(t), Event.Type.FAULT, p, "отказ обогрева камеры, печь в простое %.0f мин"
                .formatted(cfg.ovenFaultRepairMin())));
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
        if (t < readyAt[ovenNo]) return OvenState.COKING;
        return (t - readyAt[ovenNo] > cfg.toleranceMin()) ? OvenState.OVERHOLD : OvenState.READY;
    }

    public long simMinutes() { return simMin; }

    public List<Event> events() { return List.copyOf(events); }

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
