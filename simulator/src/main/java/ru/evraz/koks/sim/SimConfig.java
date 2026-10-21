package ru.evraz.koks.sim;

/**
 * Параметры симуляции. Все времена — в минутах симуляции.
 *
 * Нормативы взяты из docs/06: оборот 840 мин (14 ч), выдача ~8 мин,
 * допустимое отклонение выдачи ≤ 5 мин, минимальный период коксования —
 * приказом по комбинату (в прототипе настраивается вручную).
 *
 * @param ovenCount         число печей в батарее (норма 45–75, демо 69)
 * @param seriality         схема серийности "9-2" / "5-2" / "2-1"
 * @param cycleMin          оборот печи (T_цикл), по умолчанию 840
 * @param cokingMin         период коксования; печь должна быть готова к слоту:
 *                          cycleMin − cokingMin − chargeMin ≥ toleranceMin
 * @param chargeMin         время загрузки камер после выдачи
 * @param dischargeMin      длительность самой выдачи (машина занята)
 * @param toleranceMin      допустимая передержка (при 5 мин severity yellow)
 * @param pushJamProb       вероятность отказа выталкивания при выдаче
 * @param pushJamRepairMin  время устранения отказа выталкивания
 * @param ovenFaultPerHour  интенсивность отказов обогрева печи, 1/час
 * @param ovenFaultRepairMin время устранения отказа обогрева
 * @param seed              seed генератора случайных чисел (детерминизм)
 */
public record SimConfig(
        int ovenCount,
        String seriality,
        double cycleMin,
        double cokingMin,
        double chargeMin,
        double dischargeMin,
        double toleranceMin,
        double pushJamProb,
        double pushJamRepairMin,
        double ovenFaultPerHour,
        double ovenFaultRepairMin,
        long seed
) {
    /** Нормативный старт по документации проекта: батарея 69 печей, схема 9-2. */
    public static SimConfig defaults() {
        return new SimConfig(
                69, "9-2",
                840,   // T_цикл
                830,   // T_кокс: готовность за 5 мин до слота (без передержки в номинале)
                5,     // загрузка
                8,     // выдача
                5,     // допуск передержки
                0.004, // pushJam per выдача → ~2 раза за оборот по 69 печей
                60,    // ремонт выталкивания = 1 ч
                0.0008, // отказ обогрева, 1/час → ~0.9 отказа/сутки на батарею
                30,    // ремонт обогрева = 30 мин
                20261021L
        );
    }
}
