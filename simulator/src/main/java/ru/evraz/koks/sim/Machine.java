package ru.evraz.koks.sim;

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

    private double pos;       // позиция вдоль батареи (номер печи; башни — за торцом)
    private double freeAt;    // момент доступности для следующей задачи
    private int tasksDone;
    private double busyMin;   // переезды + приладка + операции, без ожидания

    public Machine(MachineKind kind, String name, double startPos) {
        this.kind = kind;
        this.name = name;
        this.pos = startPos;
    }

    /**
     * Момент, к которому машина доедет до целевой позиции и будет
     * приладена, если начнёт, как только освободится.
     */
    public double readyAt(double toPos, double pitchM) {
        return freeAt + kind.travelMin(pos, toPos, pitchM) + kind.setupMin;
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

    // --- состояние для экспорта (график, карта машин) ---

    public double pos() { return pos; }
    public double freeAt() { return freeAt; }
    public int tasksDone() { return tasksDone; }
    public double busyMin() { return busyMin; }

    /** Занята ли машина в момент nowMin (для статуса running/idle в API). */
    public boolean busyAt(double nowMin) { return nowMin < freeAt - 1e-9; }
}
