package ru.evraz.koks.sim.planner;

import org.junit.jupiter.api.Test;
import ru.evraz.koks.sim.SimConfig;
import ru.evraz.koks.sim.Simulator;

import static org.junit.jupiter.api.Assertions.*;

/** Метрики сравнения прогонов M1–M9 (docs/05 §6) на чистом и сорванном прогоне. */
class MetricsTest {

    private static SimConfig cfg() {
        return new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, 0.0, 60,
                1.0, 2.0, 5.0, 40.0,
                0.0, 30, 42L);
    }

    @Test
    void nominalRun_isClean() {
        var sim = new Simulator(cfg());
        sim.advance(24 * 60);
        var m = Metrics.compute(sim, 0, sim.simMinutes(), 5,
                sim.config().cokingMin(), sim.config().loadMin());

        assertEquals(118, m.totalDischarges());
        assertEquals(0, m.lateCount(), "M1: в норме просрочек нет");
        assertEquals(0, m.totalDelayMin(), 1e-9, "M2");
        assertEquals(0, m.violations(), "M6: серийность и выдача→загрузка в норме");
        assertTrue(m.maxOverholdMin() <= 5, "M9: передержка в пределах допуска: " + m.maxOverholdMin());
        assertTrue(m.calcUniformity() > 0.999, "M7: план не назначает печь раньше готовности");
        assertTrue(m.intervalStdMin() < 1, "M8: интервалы ровные: " + m.intervalStdMin());
        assertFalse(Double.isNaN(m.oeeUniformityPct()), "M3 измерима на суточном окне");
        assertTrue(m.oeeUniformityPct() > 0 && m.oeeUniformityPct() <= 100);
        assertTrue(m.machineIdleMin() > 0, "машины не заняты непрерывно");
        assertTrue(m.ovenWaitMin() > 0, "печи ждут слота — это штатный запас батареи");
    }

    @Test
    void jamRun_showsDelaysAndOverhold() {
        var sim = new Simulator(cfg());
        sim.advance(13);
        sim.injectFailure(Simulator.FailureType.PUSH_JAM);
        sim.advance(24 * 60);
        var m = Metrics.compute(sim, 0, sim.simMinutes(), 5,
                sim.config().cokingMin(), sim.config().loadMin());

        assertTrue(m.lateCount() > 0, "M1: после тугого хода есть просрочки");
        assertTrue(m.totalDelayMin() > 30, "M2: суммарная просрочка существенна");
        assertTrue(m.maxOverholdMin() > 5, "M9: передержка вышла за допуск");
        assertTrue(m.intervalStdMin() > 1, "M8: ритм рваный");
        // H4 при тугом ходе нарушается честно: камера открыта 62 мин вместо ≤ 8;
        // серийность же (H1) держится — см. PlannerTest.h1_orderHoldsUnderEveryAlgorithm
    }

    @Test
    void heatingFault_punishesGridPlan_butNotPhysicsPlan() {
        var sim = new Simulator(cfg());
        sim.advance(60);
        int p = sim.ovensAhead(10)[5];                          // печь из середины горизонта
        sim.injectOvenHeatingFault(p);                          // готовность +30 мин
        var in = ScheduleInput.of(sim, 69);

        double[] b1 = ScheduleAlgorithms.byCode("b1_fifo").build(in);
        double[] b2 = ScheduleAlgorithms.byCode("b2_idle").build(in);
        int slot = -1;
        for (int k = 0; k < in.horizon(); k++)
            if (in.ovens()[k] == p) slot = k;
        assertTrue(slot > 0);
        assertTrue(b1[slot] < in.readyAtMin()[slot] - 5,
                "эталон B1 назначает печь раньше её готовности");
        assertTrue(b2[slot] >= in.readyAtMin()[slot] - 1e-9,
                "B2 уважает готовность печи");

        var replay = Simulator.restore(sim.exportState());
        replay.setPlan(b1);
        replay.advance(240);
        var m = Metrics.compute(replay, sim.simMinutes(), replay.simMinutes(), 5,
                sim.config().cokingMin(), sim.config().loadMin());
        assertTrue(m.calcUniformity() < 1.0,
                "M7: план B1 содержит печь, которая к слоту не успевает");

        var replay2 = Simulator.restore(sim.exportState());
        replay2.setPlan(b2);
        replay2.advance(240);
        var m2 = Metrics.compute(replay2, sim.simMinutes(), replay2.simMinutes(), 5,
                sim.config().cokingMin(), sim.config().loadMin());
        assertEquals(1.0, m2.calcUniformity(), 1e-9, "план B2 реализуем по готовности печей");
    }

    @Test
    void emptyWindow_reportsZerosWithoutFractions() {
        var sim = new Simulator(cfg());
        var m = Metrics.compute(sim, 0, 0, 5,
                sim.config().cokingMin(), sim.config().loadMin());
        assertEquals(0, m.totalDischarges());
        assertTrue(Double.isNaN(m.calcUniformity()), "К_расч не определён без выдач");
        assertTrue(Double.isNaN(m.intervalStdMin()));
        assertTrue(Double.isNaN(m.oeeUniformityPct()));
        assertEquals(0, m.violations());
    }
}
