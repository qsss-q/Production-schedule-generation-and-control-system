package ru.evraz.koks.sim.planner;

/**
 * B2 idle — догон долга (docs/05 §5). Каждая выдача назначается на самое раннее
 * физически возможное время, но не раньше нормативного слота: долг гасится
 * залпом, машины идут подряд на пределе кинематики, пока поток не вернётся
 * к сетке. Плата — рваный ритм: несколько коротких интервалов, потом ровно.
 */
public final class B2Idle implements ScheduleAlgorithm {

    @Override
    public String code() { return "b2_idle"; }

    @Override
    public String title() { return "B2 idle"; }

    @Override
    public double[] build(ScheduleInput input) {
        double[] plan = new double[input.horizon()];
        for (int k = 0; k < plan.length; k++)
            plan[k] = Math.max(input.slot(k), input.earliestMin()[k]);
        return ScheduleAlgorithm.monotone(plan);
    }
}
