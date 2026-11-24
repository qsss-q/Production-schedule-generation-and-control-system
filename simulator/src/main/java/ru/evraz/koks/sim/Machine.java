package ru.evraz.koks.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Отдельная машина комплекта: позиция вдоль батареи, занятость и
 * накопленная рабочая загрузка. Симулятор спрашивает у машины
 * {@link #readyAt(double, double) готовность} и затем {@link #commit(double, double) фиксирует}
 * задачу; сама машина ничего не планирует — это делает {@link Simulator}.
 *
 * <p>Позиция задана в номерах печей (1..N), башни — за торцом батареи.
 * Пересчёт в метры: {@code (pos − 1) · шаг} для печей.
 */
public final class Machine {

    public final MachineKind kind;
    /** Отображаемое имя, например «Коксовыталкиватель». */
    public final String name;

    private double pos;        // позиция вдоль батареи (номер печи; башни — за торцом)
    private double freeAt;     // момент окончания текущей задачи
    private double holdUntil;  // до этого момента машина в простое (отказ, ремонт) и задач не берёт
    private int tasksDone;
    private double busyMin;    // запланированные переезды + операции, для совместимости API
    private final List<BusyPeriod> busyPeriods = new ArrayList<>();

    private record BusyPeriod(double startMin, double endMin) { }

    public Machine(MachineKind kind, String name, double startPos) {
        this.kind = kind;
        this.name = name;
        this.pos = startPos;
    }

    /**
     * Момент, к которому машина доедет до целевой позиции и будет
     * приладена, если начнёт, как только освободится и выйдет из простоя.
     */
    public double readyAt(double toPos, double pitchM) {
        return availableAt() + kind.travelMin(pos, toPos, pitchM) + kind.setupMin;
    }

    /** Когда машина сможет взять задачу: конец текущей задачи или конца простоя — что позже. */
    public double availableAt() {
        return Math.max(freeAt, holdUntil);
    }

    /** Простой до untilMin: задачи не берутся, текущая задача не отменяется. */
    public void hold(double untilMin) {
        holdUntil = Math.max(holdUntil, untilMin);
    }

    /** Снять простой: машина снова берёт задачи, как только освободится. */
    public void release() {
        holdUntil = 0;
    }

    /** В простое ли машина в момент nowMin. */
    public boolean downAt(double nowMin) {
        return holdUntil > nowMin + 1e-9;
    }

    /**
     * Зафиксировать задачу: машина встаёт у toPos и занята до busyUntil.
     * Задача не может закончиться раньше, чем машина освободилась.
     */
    public void commit(double toPos, double busyUntil) {
        if (busyUntil < freeAt - 1e-9)
            throw new IllegalArgumentException(
                    "машина занята до %.1f, требуют до %.1f".formatted(freeAt, busyUntil));
        freeAt = Math.max(freeAt, busyUntil);
        pos = toPos;
        tasksDone++;
    }

    /**
     * Учесть фактическую работу машины (переезд, приладка, операция).
     * Ожидание в очереди сюда не входит — иначе занятость завышается.
     */
    public void addBusy(double minutes) {
        if (minutes > 0) busyMin += minutes;
    }

    /** Реальная нагрузка до now: не считаем операции, запланированные в будущем. */
    public void addBusyPeriod(double fromMin, double untilMin) {
        if (untilMin > fromMin) busyPeriods.add(new BusyPeriod(fromMin, untilMin));
    }

    public double busyMinutesUntil(double nowMin) {
        double total = 0;
        for (BusyPeriod period : busyPeriods) {
            total += Math.max(0, Math.min(nowMin, period.endMin())
                    - Math.max(0, period.startMin()));
        }
        return total;
    }

    // --- сохранение и восстановление состояния ---

    /**
     * Снимок машины — часть снимка всей батареи ({@link Simulator#exportState()}).
     *
     * @param kind         тип машины, имя константы {@link MachineKind}
     * @param pos          позиция вдоль батареи, номера печей
     * @param freeAt       конец текущей задачи, мин симуляции
     * @param holdUntil    конец простоя (отказ, ремонт), мин симуляции; 0 — машина в работе
     * @param tasksDone    выполнено задач
     * @param busyMin      накопленная работа (переезды и операции), мин
     * @param busyPeriods  занятые периоды парами {@code [начало, конец, …]}, мин симуляции
     */
    public record Snapshot(String kind, double pos, double freeAt, double holdUntil, int tasksDone,
                           double busyMin, double[] busyPeriods) { }

    /** Снимок текущего состояния машины. */
    public Snapshot exportState() {
        double[] flat = new double[busyPeriods.size() * 2];
        for (int i = 0; i < busyPeriods.size(); i++) {
            flat[2 * i] = busyPeriods.get(i).startMin;
            flat[2 * i + 1] = busyPeriods.get(i).endMin;
        }
        return new Snapshot(kind.name(), pos, freeAt, holdUntil, tasksDone, busyMin, flat);
    }

    /** Восстановить состояние из снимка; тип машины должен совпадать. */
    public void restoreState(Snapshot snapshot) {
        if (snapshot == null)
            throw new IllegalArgumentException("нет снимка машины " + kind.code);
        if (!kind.name().equals(snapshot.kind()))
            throw new IllegalArgumentException("тип машины в снимке %s, ожидался %s"
                    .formatted(snapshot.kind(), kind.name()));
        if (!Double.isFinite(snapshot.pos()) || !Double.isFinite(snapshot.freeAt())
                || !Double.isFinite(snapshot.holdUntil()))
            throw new IllegalArgumentException("у машины " + kind.code + " позиция или время — не число");
        double[] flat = snapshot.busyPeriods();
        if (flat != null && flat.length % 2 != 0)
            throw new IllegalArgumentException("занятые периоды машины " + kind.code
                    + " должны идти парами [начало, конец]");
        pos = snapshot.pos();
        freeAt = snapshot.freeAt();
        holdUntil = Math.max(0, snapshot.holdUntil());
        tasksDone = snapshot.tasksDone();
        busyMin = snapshot.busyMin();
        busyPeriods.clear();
        if (flat != null) {
            for (int i = 0; i < flat.length; i += 2)
                addBusyPeriod(flat[i], flat[i + 1]);
        }
    }

    // --- состояние для экспорта (график, карта машин) ---

    public double pos() { return pos; }
    public double freeAt() { return freeAt; }
    public double holdUntil() { return holdUntil; }
    public int tasksDone() { return tasksDone; }
    public double busyMin() { return busyMin; }

    /** Занята ли машина в момент nowMin (для статуса running/idle в API). */
    public boolean busyAt(double nowMin) { return nowMin < freeAt - 1e-9; }
}
