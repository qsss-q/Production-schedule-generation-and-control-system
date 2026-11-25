package ru.evraz.koks.sim.planner;

import ru.evraz.koks.sim.Simulator;

/**
 * Вход планировщика: срез ближайших выдач живого прогона (docs/05 §5, §7).
 * Все алгоритмы получают один и тот же вход — так сравнение честное.
 *
 * <p>Порядок печей входит как готовый массив и не пересобирается: серийность —
 * жёсткое ограничение H1, планировщик меняет только времена.
 *
 * @param ovens           печи по порядку выдач, начиная с текущей
 * @param readyAtMin      готовность каждой из этих печей к выдаче
 * @param earliestMin     самое раннее физически возможное начало выдачи:
 *                        машины идут подряд без ожидания плана, печь — не раньше
 *                        готовности и выхода из простоя ({@link Simulator#earliestAhead});
 *                        для слота выведенной печи — текущий момент (слот пропускается)
 * @param nowMin          текущий момент прогона, мин симуляции
 * @param firstSlotMin    базовый слот первой выдачи: нормативная сетка (индекс выдачи + 1)·Δt,
 *                        от которой отсчитываются все три алгоритма
 * @param deltaMin        нормативный шаг выдачи Δt = T_цикл / N
 * @param minIntervalMin  предел форсирования: плановый интервал не может быть меньше
 *                        (docs/05 §9, ≈ 8,7 мин при Δt = 12,17; физика уже учтена в earliestMin)
 * @param rotationRemaining выдач до конца текущего оборота, включая первую
 */
public record ScheduleInput(
        int[] ovens,
        double[] readyAtMin,
        double[] earliestMin,
        double nowMin,
        double firstSlotMin,
        double deltaMin,
        double minIntervalMin,
        int rotationRemaining) {

    public ScheduleInput {
        ovens = ovens.clone();
        readyAtMin = readyAtMin.clone();
        earliestMin = earliestMin.clone();
        if (ovens.length == 0)
            throw new IllegalArgumentException("планировать нечего: пустой срез выдач");
        if (readyAtMin.length != ovens.length || earliestMin.length != ovens.length)
            throw new IllegalArgumentException("срезы готовности и физического предела не совпадают по длине");
        for (int k = 0; k < ovens.length; k++) {
            if (!Double.isFinite(readyAtMin[k]) || !Double.isFinite(earliestMin[k]))
                throw new IllegalArgumentException("готовность и физический предел должны быть числами");
        }
        if (!Double.isFinite(firstSlotMin) || !Double.isFinite(deltaMin) || deltaMin <= 0)
            throw new IllegalArgumentException("шаг выдачи Δt должен быть положительным числом");
        if (!Double.isFinite(minIntervalMin) || minIntervalMin < 0)
            throw new IllegalArgumentException("предел форсирования должен быть неотрицательным числом");
        if (minIntervalMin >= deltaMin)
            throw new IllegalArgumentException(
                    "предел форсирования %.2f мин не оставляет места сжатию: Δt = %.2f мин"
                            .formatted(minIntervalMin, deltaMin));
        if (rotationRemaining < 1)
            throw new IllegalArgumentException("до конца оборота должна оставаться хотя бы одна выдача");
    }

    /** Сколько выдач во входе. */
    public int horizon() { return ovens.length; }

    /** Базовый слот выдачи с позицией k: нормативная сетка от первой выдачи. */
    public double slot(int k) { return firstSlotMin + k * deltaMin; }

    /**
     * Долг потока перед базовой сеткой: насколько ближайшая выдача ушла вперёд
     * от нормативного слота (занятость машин, простой). 0 — поток в норме.
     */
    public double debtMin() {
        return Math.max(0, earliestMin[0] - firstSlotMin);
    }

    /** Предел форсирования по docs/05 §9: интервал не меньше 60/(60/Δt + 2) мин. */
    public static double forcingFloorMin(double deltaMin) {
        return 60.0 / (60.0 / deltaMin + 2.0);
    }

    /**
     * Срез входа из живого прогона. Горизонт — сколько выдач планировать,
     * но не дальше конца текущего оборота: за оборотом готовности печей
     * уже не те, что в срезе, и печь попала бы в план дважды.
     * Предел форсирования — по умолчанию расчётный {@link #forcingFloorMin(double)}.
     */
    public static ScheduleInput of(Simulator sim, int horizon) {
        return of(sim, horizon, sim.deltaMin());
    }

    /**
     * То же с переопределением шага сетки: ручка «допустимый шаг между выдачами»
     * из панели управления. План строится с этим шагом; порядок печей и физические
     * пределы не меняются. Шаг заметно меньше предела форсирования не принимается.
     */
    public static ScheduleInput of(Simulator sim, int horizon, double stepMinOverride) {
        int h = Math.max(1, Math.min(horizon, sim.rotationRemaining()));
        double step = Double.isFinite(stepMinOverride) && stepMinOverride > 0
                ? stepMinOverride : sim.deltaMin();
        return new ScheduleInput(
                sim.ovensAhead(h),
                sim.readinessAhead(h),
                sim.earliestAhead(h),
                sim.simMinutes(),
                (sim.nextIssue() + 1) * step,
                step,
                forcingFloorMin(step),
                sim.rotationRemaining());
    }
}
