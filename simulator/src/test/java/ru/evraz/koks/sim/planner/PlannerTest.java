package ru.evraz.koks.sim.planner;

import org.junit.jupiter.api.Test;
import ru.evraz.koks.sim.SimConfig;
import ru.evraz.koks.sim.Simulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сравнение B1/B2/B3 на одном входе (docs/05 §5–§6): без возмущений все три
 * совпадают; при долге B2 гасит его залпом, B3 — равномерно; порядок печей
 * (H1) не меняет ни один.
 */
class PlannerTest {

    private static SimConfig cfg() {
        return new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, 0.0, 60,
                1.0, 2.0, 5.0, 40.0,
                0.0, 30, 42L);
    }

    /** Прогон с тугим ходом в начале: поток застрял, машины отстали от нормативной сетки. */
    private static Simulator jammed(long minutesAfterJam) {
        var sim = new Simulator(cfg());
        sim.advance(13);                                        // первая выдача по сетке
        sim.injectFailure(Simulator.FailureType.PUSH_JAM);      // тугой ход на второй
        sim.advance(minutesAfterJam);
        return sim;
    }

    private static double[] plan(String code, ScheduleInput in) {
        return ScheduleAlgorithms.byCode(code).build(in);
    }

    @Test
    void withoutDisturbance_allThreeMatchGrid() {
        var sim = new Simulator(cfg());
        sim.advance(60);
        var in = ScheduleInput.of(sim, 69);

        double[] b1 = plan("b1_fifo", in);
        assertArrayEquals(b1, plan("b2_idle", in), 1e-9, "без возмущений B2 = B1");
        assertArrayEquals(b1, plan("b3_smooth", in), 1e-9, "без возмущений B3 = B1");
        for (int k = 0; k < b1.length; k++)
            assertEquals(in.slot(k), b1[k], 1e-9, "эталон — нормативная сетка k·Δt");
    }

    @Test
    void disturbed_b2PlansPhysicalEarliest_andPlanIsImplementable() {
        var sim = jammed(120);
        var in = ScheduleInput.of(sim, 69);
        assertTrue(in.debtMin() > 10, "долг потока после тугого хода: " + in.debtMin());

        double[] b2 = plan("b2_idle", in);
        for (int k = 0; k < b2.length; k++) {
            assertTrue(b2[k] >= in.readyAtMin()[k] - 1e-9,
                    "выдача не раньше готовности печи, слот " + k);
            assertTrue(b2[k] >= in.earliestMin()[k] - 1e-9,
                    "выдача не раньше физического предела машин, слот " + k);
            assertTrue(k == 0 || b2[k] >= b2[k - 1], "план не убывает");
        }
        int compressed = 0;
        for (int k = 1; k < b2.length; k++)
            if (b2[k] - b2[k - 1] < in.deltaMin() - 1e-9) compressed++;
        assertTrue(compressed > 0, "B2 сжимает интервалы, гася долг");
        assertTrue(compressed < b2.length - 1, "сжатие конечно: долг гасится и поток возвращается к сетке");

        // план реализуем: факт совпадает с планом — физический предел был честным
        var replay = Simulator.restore(sim.exportState());
        replay.setPlan(b2);
        replay.advance(240);
        var h = replay.dischargeHistory();
        assertEquals(sim.discharges(), replay.planStart(), "план начинается с текущей выдачи");
        for (int i = sim.discharges(); i < h.size(); i++)
            assertEquals(h.get(i).plannedMin(), h.get(i).actualMin(), 1.0,
                    "факт идёт по плану B2, выдача " + i);
    }

    @Test
    void disturbed_b1KeepsGrid_andPaysWithDeviations() {
        var sim = jammed(120);
        var in = ScheduleInput.of(sim, 69);
        double[] b1 = plan("b1_fifo", in);
        for (int k = 0; k < b1.length; k++)
            assertEquals(in.slot(k), b1[k], 1e-9, "эталон держит нормативную сетку");

        var replay = Simulator.restore(sim.exportState());
        replay.setPlan(b1);
        replay.advance(240);
        long late = replay.dischargeHistory().stream()
                .filter(r -> r.actualMin() - r.plannedMin() > 5)
                .count();
        assertTrue(late > 0, "B1 не гасит долг: факт отстаёт от норматива");

        // на том же входе B2 просрочек не даёт
        var replay2 = Simulator.restore(sim.exportState());
        replay2.setPlan(plan("b2_idle", in));
        replay2.advance(240);
        // просрочки считаем только с начала плана: до него были догоняющие
        // выдачи после тугого хода — их задержка к плану отношения не имеет
        long late2 = replay2.dischargeHistory().stream()
                .skip(sim.discharges())
                .filter(r -> r.actualMin() - r.plannedMin() > 5)
                .count();
        assertEquals(0, late2, "план B2 реализуем, просрочек против него нет");
    }

    @Test
    void disturbed_b3SpreadsDebt_recoveringSlowerThanB2() {
        var sim = jammed(120);
        var in = ScheduleInput.of(sim, 69);
        double[] b2 = plan("b2_idle", in);
        double[] b3 = plan("b3_smooth", in);

        // B3 гасит долг равномерно: разброс интервалов плана ниже, чем у B2
        assertTrue(std(intervals(b3)) < std(intervals(b2)),
                "интервалы B3 ровнее: %.2f против %.2f".formatted(std(intervals(b3)), std(intervals(b2))));

        // B3 возвращается к сетке позже: долю гасит к концу оборота, B2 — быстрее
        int repaidB2 = repaidAt(in, b2);
        int repaidB3 = repaidAt(in, b3);
        assertTrue(repaidB2 < repaidB3,
                "B2 гасит долг к слоту %d, B3 — только к %d".formatted(repaidB2, repaidB3));

        // после погашения долга B3 идёт ровно по сетке, не обгоняя её
        for (int k = repaidB3; k < b3.length; k++)
            assertEquals(in.slot(k), b3[k], 1e-9, "B3 после погашения долга — по сетке, слот " + k);
    }

    @Test
    void disturbed_b3RespectsReadinessAndPhysics() {
        var sim = jammed(120);
        sim.injectOvenHeatingFault(sim.ovensAhead(10)[5]);      // готовность одной печи уезжает
        var in = ScheduleInput.of(sim, 69);
        double[] b3 = plan("b3_smooth", in);
        for (int k = 0; k < b3.length; k++) {
            assertTrue(b3[k] >= in.readyAtMin()[k] - 1e-9, "B3 не раньше готовности печи, слот " + k);
            assertTrue(b3[k] >= in.earliestMin()[k] - 1e-9, "B3 не раньше физического предела, слот " + k);
            assertTrue(k == 0 || b3[k] >= b3[k - 1], "план не убывает");
        }
    }

    @Test
    void h1_orderHoldsUnderEveryAlgorithm() {
        for (String code : List.of("b1_fifo", "b2_idle", "b3_smooth")) {
            var sim = jammed(120);
            var in = ScheduleInput.of(sim, 69);
            var replay = Simulator.restore(sim.exportState());
            replay.setPlan(plan(code, in));
            replay.advance(480);
            var m = Metrics.compute(replay, sim.simMinutes(), replay.simMinutes(),
                    5, sim.config().cokingMin(), sim.config().loadMin());
            assertEquals(0, m.violations(), code + ": H1/H4 нарушены");
        }
    }

    @Test
    void input_rejectsInconsistentSlices() {
        var sim = new Simulator(cfg());
        sim.advance(30);

        assertEquals(sim.rotationRemaining(), ScheduleInput.of(sim, 1000).horizon(),
                "горизонт ограничен концом текущего оборота");
        assertEquals(1, ScheduleInput.of(sim, 0).horizon(), "минимум одна выдача");

        double delta = sim.deltaMin();
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleInput(new int[0], new double[0], new double[0], 0, 0, delta, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleInput(new int[2], new double[1], new double[2], 0, 0, delta, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleInput(new int[1], new double[]{Double.NaN}, new double[]{0}, 0, 0, delta, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleInput(new int[1], new double[]{0}, new double[]{0}, 0, 0, 0, 1, 1),
                "Δt должен быть положительным");
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleInput(new int[1], new double[]{0}, new double[]{0}, 0, 0, delta, delta, 1),
                "предел форсирования ≥ Δt не оставляет места сжатию");
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleInput(new int[1], new double[]{0}, new double[]{0}, 0, 0, delta, 1, 0));
    }

    /** Первый слот плана, где поток вернулся к нормативной сетке. */
    private static int repaidAt(ScheduleInput in, double[] plan) {
        for (int k = 1; k < plan.length; k++)
            if (plan[k] <= in.slot(k) + 1e-9) return k;
        return plan.length;
    }

    private static double[] intervals(double[] plan) {
        double[] out = new double[plan.length - 1];
        for (int k = 0; k < out.length; k++) out[k] = plan[k + 1] - plan[k];
        return out;
    }

    private static double std(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double sq = 0;
        for (double v : values) sq += (v - mean) * (v - mean);
        return Math.sqrt(sq / values.length);
    }
}
