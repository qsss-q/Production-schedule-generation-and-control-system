package ru.evraz.koks.sim;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Демонстрация v0.1 в консоли: 3 суток батареи 69 печей, схема 9-2,
 * машины с перемещениями и загрузкой через башни.
 * Запуск: ./gradlew run
 */
public final class DemoMain {

    public static void main(String[] args) {
        var cfg = SimConfig.defaults();
        var sim = new Simulator(cfg);
        var scheme = Seriality.Scheme.parse(cfg.seriality());

        System.out.printf(
                "Симулятор v0.1: батарея %d печей, серийность %s (S=%d серий, K=%d),%n" +
                "T_цикл %.0f мин, T_кокс %.0f мин, Δt выдачи %.1f мин, шаг %.2f м, seed %d%n%n",
                cfg.ovenCount(), scheme, scheme.series(), scheme.seriesStep(),
                cfg.cycleMin(), cfg.cokingMin(), cfg.cycleMin() / cfg.ovenCount(),
                cfg.pitchM(), cfg.seed());

        var order = Seriality.dischargeOrder(cfg.ovenCount(), scheme);
        System.out.println("Порядок выдачи за оборот: " + preview(order) + "…");
        System.out.println("Машины комплекта: " + sim.machineStatus());
        System.out.println();

        long days = 3;
        long step = days * 24 * 60 / 9; // 9 контрольных точек
        for (long i = 1; i <= 9; i++) {
            sim.advance(step);
            System.out.println();
            System.out.printf("=== t = %.1f ч — выдач %d, отказов %d (выталкивание %d, обогрев %d) ===%n",
                    sim.simMinutes() / 60.0, sim.discharges(), sim.pushJams() + sim.ovenFaults(),
                    sim.pushJams(), sim.ovenFaults());
            printStateCounts(sim, cfg.ovenCount());
            System.out.println("Машины: " + sim.machineStatus());
            System.out.println("Последние события:");
            var tail = sim.events();
            for (int e = Math.max(0, tail.size() - 5); e < tail.size(); e++)
                System.out.println("  " + tail.get(e));
        }

        System.out.println();
        System.out.println("=== Итог за " + days + " суток ===");
        System.out.printf("Выдач: %d (нормативный интервал %.1f мин, факт среднее %.1f мин)%n",
                sim.discharges(), cfg.cycleMin() / cfg.ovenCount(), sim.avgIntervalMin());
        System.out.printf("Опозданий к слоту: %d, передержек > %.0f мин: %d%n",
                sim.lateCount(), cfg.toleranceMin(), sim.overholdCount());
        System.out.printf("Отказов: выталкивание %d, обогрев камер %d%n",
                sim.pushJams(), sim.ovenFaults());
        System.out.println("Утилизация машин: " + sim.machineStatus());
    }

    private static void printStateCounts(Simulator sim, int ovenCount) {
        var counts = new TreeMap<OvenState, Integer>();
        for (int p = 1; p <= ovenCount; p++)
            counts.merge(sim.state(p), 1, Integer::sum);
        System.out.println("Состояния печей: " + counts);
    }

    private static String preview(int[] order) {
        var sb = new StringBuilder();
        for (int i = 0; i < Math.min(20, order.length); i++)
            sb.append(order[i]).append(i < Math.min(20, order.length) - 1 ? ", " : "");
        return sb.toString();
    }
}
