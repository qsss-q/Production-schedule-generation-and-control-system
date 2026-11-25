package ru.evraz.koks.sim.planner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.evraz.koks.sim.SimConfig;
import ru.evraz.koks.sim.Simulator;

import java.util.List;
import java.util.Locale;

/**
 * Эксперимент для слайдов 10–11. Ручной запуск — при обычной сборке
 * пропускается (Assumptions), чтобы 50 суточных прогонов не гонялись
 * на каждый тест:
 * {@code bash gradlew test --tests "*ExperimentRunner" -Dexperiment=1}
 * Пять режимов отказов × 10 повторов, окно 24 ч, базовый сценарий (сетка k·Δt,
 * как B1 без плана). Плюс сравнение B1/B2/B3 на одном входе — реплей с той же
 * точки, тот же seed, перепланирование на границе плана.
 */
class ExperimentRunner {

    private static SimConfig cfg(double jam, double heat, long seed) {
        return new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, jam, 60,
                1.0, 2.0, 5.0, 40.0,
                heat, 30, seed);
    }

    @Test
    void run() {
        Assumptions.assumeTrue(Boolean.getBoolean("experiment") || System.getenv("EXPERIMENT") != null,
                "ручной эксперимент: запускать с EXPERIMENT=1 (env) или -Pexperiment=1");
        System.err.println("EXPERIMENT-BEGIN");
        record Mode(String name, double jam, double heat) { }
        List<Mode> modes = List.of(
                new Mode("Без отказов", 0.0, 0.0),
                new Mode("Штатный", 0.004, 0.0008),
                new Mode("Отказы выталкивания", 0.02, 0.0),
                new Mode("Отказы обогрева", 0.0, 0.005),
                new Mode("Комбинированный", 0.02, 0.005));
        System.err.println("| Режим | Просрочек >5 мин, % (10 повторов) | Суммарная просрочка, мин | Макс. передержка, мин |");
        System.err.println("|---|---|---|---|");
        for (Mode mode : modes) {
            double sumLatePct = 0;
            double sumDelay = 0;
            double maxOver = 0;
            for (long seed = 1; seed <= 10; seed++) {
                var sim = new Simulator(cfg(mode.jam, mode.heat, seed * 7919L));
                sim.advance(24 * 60);
                var m = Metrics.compute(sim, 0, sim.simMinutes(), 5,
                        sim.config().cokingMin(), sim.config().loadMin());
                sumLatePct += 100.0 * m.lateCount() / Math.max(1, m.totalDischarges());
                sumDelay += m.totalDelayMin();
                maxOver = Math.max(maxOver, m.maxOverholdMin());
            }
            System.err.printf(Locale.ROOT, "| %s | %.1f | %.0f | %.0f |%n",
                    mode.name, sumLatePct / 10, sumDelay / 10, maxOver);
        }

        // Сравнение B1/B2/B3: отказ в начале окна, детерминированный реплей
        System.err.println();
        System.err.println("Сравнение алгоритмов: тугой ход на 2-й выдаче, окно 2 оборота с момента планирования");
        System.err.println("| Алгоритм | Просрочено (M1) | Суммарно, мин (M2) | Равномерность ОЕЭ % (M3) | Простой, мин (M5) | Разброс интервалов (M8) | Макс. передержка (M9) |");
        System.err.println("|---|---|---|---|---|---|---|");
        var base = new Simulator(cfg(0.0, 0.0, 42L));
        base.advance(13);
        base.injectFailure(Simulator.FailureType.PUSH_JAM);
        base.advance(120);
        double from = base.simMinutes();
        var snapshot = base.exportState();
        for (var algo : ScheduleAlgorithms.all()) {
            // реплей с перепланированием на границе каждого плана — как живая система
            var copy = Simulator.restore(snapshot);
            double to = from + 2 * 840;
            while (copy.simMinutes() < to - 1e-9) {
                var in = ScheduleInput.of(copy, Integer.MAX_VALUE);
                copy.setPlan(algo.build(in));
                copy.advance((long) Math.min(Math.ceil(planEnd(algo, copy)), Math.ceil(to)) - (long) copy.simMinutes());
            }
            var m = Metrics.compute(copy, from, to, 5, 830, 5);
            System.err.printf(Locale.ROOT, "| %s | %d | %.0f | %.1f | %.0f | %.2f | %.0f |%n",
                    algo.code(), m.lateCount(), m.totalDelayMin(),
                    Double.isFinite(m.oeeUniformityPct()) ? m.oeeUniformityPct() : Double.NaN,
                    m.machineIdleMin() + m.ovenWaitMin(), m.intervalStdMin(), m.maxOverholdMin());
        }
        System.err.println("EXPERIMENT-END");
    }

    private static double planEnd(ScheduleAlgorithm algo, Simulator copy) {
        var in = ScheduleInput.of(copy, Integer.MAX_VALUE);
        double[] plan = algo.build(in);
        return plan[plan.length - 1];
    }
}
