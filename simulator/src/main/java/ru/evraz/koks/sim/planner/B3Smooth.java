package ru.evraz.koks.sim.planner;

/**
 * B3 smooth — сглаживание долга (docs/05 §5). Тот же долг, что гасит B2,
 * размазывается равномерно по остатку оборота: интервал постоянный
 * Δt − δ, где δ = долг / выдач до конца оборота, но не больше предела
 * форсирования. План возвращается к нормативной сетке ровно к концу
 * оборота и дальше не обгоняет её. Физический предел готовности печей
 * и машин зажимает линию снизу — если готовность не даёт сжать,
 * фактическое сжатие меньше, остаток долга живёт в плане как сдвиг.
 */
public final class B3Smooth implements ScheduleAlgorithm {

    @Override
    public String code() { return "b3_smooth"; }

    @Override
    public String title() { return "B3 smooth"; }

    @Override
    public double[] build(ScheduleInput input) {
        double debt = input.debtMin();
        int remaining = Math.max(1, input.rotationRemaining());
        double maxSqueeze = input.deltaMin() - input.minIntervalMin();
        double squeeze = Math.min(debt / remaining, Math.max(0, maxSqueeze));
        double step = input.deltaMin() - squeeze;

        double[] plan = new double[input.horizon()];
        double prev = input.firstSlotMin() + input.debtMin();   // первая выдача — по факту
        plan[0] = prev;
        for (int k = 1; k < plan.length; k++) {
            // равномерный шаг от факта, но не раньше физического предела;
            // линия сжатия идёт выше сетки, пока долг не погашен, и держится
            // за сетку после — норматив не обгоняем
            prev = Math.max(input.earliestMin()[k],
                    Math.max(prev + step, input.slot(k)));
            plan[k] = prev;
        }
        return ScheduleAlgorithm.monotone(plan);
    }
}
