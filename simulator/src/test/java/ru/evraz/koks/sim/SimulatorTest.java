package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Поведение движка: слоты, передержки, отказы, детерминизм. */
class SimulatorTest {

    private static SimConfig cfg(double cokingMin, double pushJamProb) {
        return new SimConfig(69, "9-2", 840, cokingMin, 5, 8, 5,
                pushJamProb, 60, 0.0, 30, 42L);
    }

    @Test
    void nominalRun_dischargesEveryDeltaAndNoOverhold() {
        // Без отказов; период коксования 830 → печь готова к слоту за 5 мин,
        // это равно допуску, поэтому передержек нет.
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(24 * 60);
        // За сутки при Δt = 12.17 мин плановых слотов 118
        assertEquals(118, sim.discharges());
        assertEquals(0, sim.pushJams());
        assertEquals(0, sim.ovenFaults());
        assertEquals(0, sim.overholdCount());
        // Без отказов события — только выдачи
        assertTrue(sim.events().stream().allMatch(e -> e.type() == Event.Type.DISCHARGE));
    }

    @Test
    void whenCokeTooEarly_ovensWaitOverTolerance() {
        // Период коксования короче: печь готова (в стационаре) за 10 мин
        // до слота — ожидание 10 мин > допуска 5, передержка у каждой выдачи.
        var sim = new Simulator(cfg(825, 0.0));
        sim.advance(24 * 60);
        assertEquals(118, sim.overholdCount(),
                "все выдачи должны быть с передержкой");
    }

    @Test
    void pushJam_alwaysFails_shiftsFlowByRepairTime() {
        // Каждая выдача заканчивается отказом и ремонтом 60 мин: машина
        // проезжает печь примерно раз в (60 + 8) мин вместо 12.17.
        var sim = new Simulator(cfg(830, 1.0));
        sim.advance(24 * 60);
        assertTrue(sim.discharges() < 100, "поток должен замедлиться: " + sim.discharges() + " выдач");
        assertEquals(sim.discharges(), sim.pushJams(), "каждая выдача потребовала ремонт");
        assertTrue(sim.overholdCount() > 0,
                "печи, задержавшиеся из-за занятой машины, должны получить передержку");
        assertTrue(sim.events().stream().anyMatch(e -> e.type() == Event.Type.FAULT));
    }

    @Test
    void heatFault_prolongsCoking_andYieldsLate() {
        // интенсифицируем отказ обогрева: 1 отказ в час на печь
        var cfg = new SimConfig(69, "9-2", 840, 830, 5, 8, 5, 0.0, 60, 1.0, 30, 7L);
        var sim = new Simulator(cfg);
        sim.advance(24 * 60);
        assertTrue(sim.ovenFaults() > 0);
        assertTrue(sim.lateCount() > 0, "печи с повышенным коксованием не успевают к слоту");
    }

    @Test
    void sameSeed_givesIdenticalEventLog() {
        var a = new Simulator(SimConfig.defaults());
        var b = new Simulator(SimConfig.defaults());
        a.advance(3 * 24 * 60);
        b.advance(3 * 24 * 60);
        assertEquals(a.events(), b.events());
        assertEquals(a.discharges(), b.discharges());
    }

    @Test
    void advanceIsResumable() {
        var a = new Simulator(SimConfig.defaults());
        var b = new Simulator(SimConfig.defaults());
        a.advance(60);
        a.advance(60);
        b.advance(120);
        assertEquals(a.events(), b.events());
        assertEquals(a.discharges(), b.discharges());
    }

    @Test
    void stateTransition_afterDischargeOvenGoesCokingThenReady() {
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(13); // первая выдача в Δt = 12.17 мин
        assertEquals(1, sim.discharges());
        // Печь 1 после выдачи должна быть коксоваться до готовности
        assertEquals(OvenState.COKING, sim.state(1));
        // Печь 2 (слот в 24.3 мин) ещё в работе, готова только к своему слоту
        assertEquals(OvenState.COKING, sim.state(2));
    }
}
