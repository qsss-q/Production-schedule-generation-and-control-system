package ru.evraz.koks.sim;

/**
 * Состояние печи. Соответствуетstatuses из модельб документации docs/02 §3.
 */
public enum OvenState {
    /** Печь под загрузкой — идёт процесс коксования. */
    COKING("коксование"),
    /** Кокс готов и ждёт своего места в выдачном потоке. */
    READY("готова к выдаче"),
    /** Кокс готов, но печь стоит дольше допустимого (severity yellow). */
    OVERHOLD("передержка"),
    /** Печь в аварийном состоянии (ампераж/отказ), выдача невозможна. */
    FAULT("авария");

    public final String label;

    OvenState(String label) { this.label = label; }
}
