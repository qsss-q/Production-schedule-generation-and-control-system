package ru.evraz.koks.sim.planner;

/**
 * B1 FIFO — равномерный эталон (docs/05 §5). Выдачи строго по базовой сетке
 * k·Δt, готовность печей и занятость машин не учитываются: все срывы
 * «съезжают» на следующие печи цикла, поток возвращается к норме только
 * за счёт запаса батареи. Это нижняя граница, от которой считается
 * выигрыш B2 и B3.
 */
public final class B1Fifo implements ScheduleAlgorithm {

    @Override
    public String code() { return "b1_fifo"; }

    @Override
    public String title() { return "B1 FIFO"; }

    @Override
    public double[] build(ScheduleInput input) {
        double[] plan = new double[input.horizon()];
        for (int k = 0; k < plan.length; k++)
            plan[k] = input.slot(k);
        return ScheduleAlgorithm.monotone(plan);
    }
}
