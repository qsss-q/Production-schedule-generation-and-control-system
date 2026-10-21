package ru.evraz.koks.sim;

/**
 * Тип машины комплекта. Скорости — паспортные и оценочные ТТХ из практики;
 * документы проекта их не нормируют, поэтому значения собраны в одну точку
 * правды и помечены «уточнить по паспортам ЕКС» (docs/06).
 *
 * <p>Маршевые скорости взяты из паспортов машин коксового цеха:
 * коксовыталкиватель — 1,7 м/с, углезагрузочный вагон — до 2,5 м/с,
 * тушильный вагон / электровоз — 1,5 м/с, двересъёмная машина — 1,5 м/с.
 * Приём кокса в тушильный вагон идёт со скоростью штанги (0,5 м/с) и
 * учтён в длительности выталкивания, а не в переезде. Малые скорости
 * двересъёмной машины (0,2 / 0,06 м/с) относятся к снятию и установке
 * двери и учтены в doorRemoveMin / doorInstallMin.
 *
 * <p>Время переезда считается по трапецеидальному профилю:
 * <pre>
 *   L ≥ v²/a :  t = L/v + v/a          (разгон + марш + торможение)
 *   L &lt; v²/a :  t = 2·√(L/a)          (маршевая скорость не достигается)
 * </pre>
 * Приладка у печи (центровка) входит в {@link #setupMin} и считается отдельно.
 *
 * @param code          машинный код (КВ / ДСМ / ТВ / УВ, как в docs/02)
 * @param speedMPerMin  маршевая скорость переезда, м/мин
 * @param accelMPerSec2 ускорение, м/с² — определяет разгон/торможение
 * @param setupMin      приладка у печи перед операцией, мин
 */
public enum MachineKind {
    /** Коксовыталкиватель (машинная сторона, выталкивание пирога). */
    PUSHING("КВ", 102, 0.4, 1.0),
    /** Двересъёмная машина: снимает дверь до выталкивания, ставит после загрузки. */
    DOOR_MACHINE("ДСМ", 90, 0.3, 0.5),
    /** Тушильный вагон: принимает пирог, везёт к тушильной башне, разгружает. */
    QUENCH_CAR("ТВ", 90, 0.3, 0.5),
    /** Углезагрузочный вагон: берёт шихту у угольной башни, загружает камеру. */
    LOADING_CAR("УВ", 150, 0.4, 0.5);

    public final String code;
    public final double speedMPerMin;
    public final double accelMPerSec2;
    public final double setupMin;

    MachineKind(String code, double speedMPerMin, double accelMPerSec2, double setupMin) {
        this.code = code;
        this.speedMPerMin = speedMPerMin;
        this.accelMPerSec2 = accelMPerSec2;
        this.setupMin = setupMin;
    }

    /**
     * Чистое время переезда от позиции from до позиции to, мин.
     * Координаты — номера печей; башни — за торцом батареи
     * (см. {@link Simulator#towerPos()}).
     */
    public double travelMin(double from, double to, double pitchM) {
        double l = Math.abs(to - from) * pitchM;
        if (l <= 1e-9) return 0;
        double v = speedMPerMin / 60.0;   // м/с
        double a = accelMPerSec2;         // м/с²
        double lBrake = v * v / a;        // путь разгона до v и торможения
        double tSec = (l <= lBrake)
                ? 2.0 * Math.sqrt(l / a)
                : 2.0 * (v / a) + (l - lBrake) / v;
        return tSec / 60.0;
    }
}
