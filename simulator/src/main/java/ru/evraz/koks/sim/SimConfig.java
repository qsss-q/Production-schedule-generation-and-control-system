package ru.evraz.koks.sim;

/**
 * Параметры симуляции v0.1. Все времена — в минутах симуляции,
 * длины — в метрах. Длительности работ — нормативы docs/06.
 *
 * <p>Выдача и загрузка печи — задачи машин комплекта (см. {@link MachineKind}),
 * поэтому в конфигурации заданы длительности отдельных операций, а не
 * «выдача целиком».
 *
 * @param ovenCount          число печей в батарее (норма 45–75, демо 69)
 * @param seriality          схема серийности "9-2" / "5-2" / "2-1"
 * @param cycleMin           оборот печи (T_цикл), по умолчанию 840
 * @param cokingMin          период коксования
 * @param toleranceMin       допустимая передержка (при 5 мин severity yellow)
 * @param pitchM             шаг осей камер, м (КБ-5 — 1,26; КБ-7 — 1,40)
 * @param pushMin            длительность выталкивания пирога, мин
 * @param doorRemoveMin      снятие двери камеры двересъёмной машиной, мин
 * @param doorInstallMin     установка двери после загрузки, мин
 * @param pushJamProb        вероятность отказа выталкивания (тугой ход) при выдаче
 * @param pushJamRepairMin   время устранения отказа выталкивания
 * @param takeMin            приём пирога в кузов тушильного вагона, мин
 * @param unloadMin          разгрузка тушильного вагона у башни, мин
 * @param loadMin            загрузка камеры шихтой углезагрузочным вагоном, мин
 * @param towerDistanceM     расстояние от торца батареи до тушильной/угольной башни, м
 * @param ovenFaultPerHour   интенсивность отказов обогрева печи, 1/час
 * @param ovenFaultRepairMin время устранения отказа обогрева
 * @param seed               seed генератора случайных чисел (детерминизм)
 */
public record SimConfig(
        int ovenCount,
        String seriality,
        double cycleMin,
        double cokingMin,
        double toleranceMin,
        double pitchM,
        double pushMin,
        double doorRemoveMin,
        double doorInstallMin,
        double pushJamProb,
        double pushJamRepairMin,
        double takeMin,
        double unloadMin,
        double loadMin,
        double towerDistanceM,
        double ovenFaultPerHour,
        double ovenFaultRepairMin,
        long seed
) {
    /** Нормативный старт по документации проекта: батарея 69 печей, схема 9-2. */
    public static SimConfig defaults() {
        return new SimConfig(
                69,        // печей
                "9-2",     // серийность
                840,       // T_цикл = 14 ч
                830,       // T_кокс: готовность к слоту с запасом ~3 мин
                5,         // допуск передержки
                1.26,      // шаг осей камер КБ-5, м
                2.0,       // выталкивание
                1.0,       // снятие двери
                1.0,       // установка двери
                0.004,     // pushJam: ~2 раза за оборот на 69 печей
                60,        // ремонт выталкивания = 1 ч
                1.0,       // приём пирога
                2.0,       // разгрузка у башни
                5.0,       // загрузка камеры
                40.0,      // от торца батареи до башни
                0.0008,    // отказ обогрева, 1/час → ~0.9 отказа/сутки
                30,        // ремонт обогрева = 30 мин
                20261021L
        );
    }
}
