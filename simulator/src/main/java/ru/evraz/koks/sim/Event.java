package ru.evraz.koks.sim;

/**
 * Событие симуляции. В v0 все времена — в минутах симуляции.
 *
 * @param simMin  минута симуляции, когда событие произошло
 * @param type    тип события
 * @param ovenNo  номер печи (1..N)
 * @param detail  пояснение, лично сведено к строке для консоли
 */
public record Event(long simMin, Type type, int ovenNo, String detail) {

    public enum Type {
        /** Выдача печи выполнена. */
        DISCHARGE,
        /** Выдача прошла позже слота (машина занята, печь не готова). */
        LATE,
        /** Печь ждала выдачи дольше допустимого (toleranceMin). */
        OVERHOLD,
        /** Отказ выталкивания или обогрева — печь в FAULT. */
        FAULT,
        /** Отказ устранён, печь вернулась в работу. */
        RECOVERED
    }

    @Override
    public String toString() {
        return String.format("[%6d мин] %-10s печь %2d %s", simMin, type, ovenNo, detail);
    }
}
