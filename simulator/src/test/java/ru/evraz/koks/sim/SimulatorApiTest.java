package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API симулятора для внешнего слоя (Spring-бэкенд): снимки состояния,
 * прогноз выдач и принудительные отказы.
 */
class SimulatorApiTest {

    private static SimConfig cfg(double pushJamProb) {
        return new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, pushJamProb, 60,
                1.0, 2.0, 5.0, 40.0,
                0.0, 30, 42L);
    }

    @Test
    void metadata_isConsistent() {
        var sim = new Simulator(cfg(0));
        assertEquals(69, sim.ovenCount());
        assertEquals(840.0 / 69, sim.deltaMin(), 1e-12);
        assertEquals(0, sim.nextIssue());
        assertEquals(69, sim.order().length);
        assertEquals(1, sim.order()[0]);
        assertEquals(10, sim.order()[1]);
        assertSame(sim.config(), sim.config());
    }

    @Test
    void order_isDefensiveCopy() {
        var sim = new Simulator(cfg(0));
        int[] order = sim.order();
        order[0] = 999;
        assertEquals(1, sim.order()[0]);
    }

    @Test
    void ovenGetters_matchTimeline() {
        var sim = new Simulator(cfg(0));
        sim.advance(60);

        assertTrue(sim.dischargeAt(1) > 12 && sim.dischargeAt(1) < 13,
                "первая выдача около слота 12.17: " + sim.dischargeAt(1));
        assertTrue(sim.loadDoneAt(1) > sim.dischargeAt(1));
        assertTrue(sim.readyAt(1) > 800, "после загрузки печь коксуется 830 мин");
        assertEquals(OvenState.COKING, sim.state(1));

        long eventMin = sim.events().stream()
                .filter(e -> e.type() == Event.Type.DISCHARGE && e.ovenNo() == 1)
                .findFirst().orElseThrow().simMin();
        assertEquals((long) Math.ceil(sim.dischargeAt(1) - 1e-9), eventMin);
    }

    @Test
    void ovenGetters_rejectOutOfRange() {
        var sim = new Simulator(cfg(0));
        assertThrows(IllegalArgumentException.class, () -> sim.readyAt(0));
        assertThrows(IllegalArgumentException.class, () -> sim.loadDoneAt(70));
        assertThrows(IllegalArgumentException.class, () -> sim.dischargeAt(-1));
    }

    @Test
    void forecast_followsSerialityAndSlots() {
        var sim = new Simulator(cfg(0));
        List<Simulator.PlannedDischarge> f = sim.forecast(3);

        assertEquals(List.of(1, 10, 19), f.stream().map(Simulator.PlannedDischarge::ovenNo).toList());
        assertEquals(sim.deltaMin(), f.get(0).plannedMin(), 1e-9);
        assertEquals(2 * sim.deltaMin(), f.get(1).plannedMin(), 1e-9);
        assertEquals(f.get(0).plannedMin(), f.get(0).predictedStartMin(), 1e-9,
                "в стационаре машины успевают к слоту");
        for (int i = 1; i < f.size(); i++)
            assertTrue(f.get(i).plannedMin() > f.get(i - 1).plannedMin());
    }

    @Test
    void forecast_isBoundedByOvenCount_andDoesNotMutate() {
        var sim = new Simulator(cfg(0));
        int before = sim.nextIssue();
        assertEquals(69, sim.forecast(1000).size());
        assertEquals(before, sim.nextIssue());
    }

    @Test
    void injectedPushJam_happensOnce_andIsReported() {
        var sim = new Simulator(cfg(0));
        sim.injectFailure(Simulator.FailureType.PUSH_JAM);
        sim.advance(24 * 60);

        assertEquals(1, sim.pushJams(), "форсированный отказ срабатывает один раз");
        assertTrue(sim.events().stream().anyMatch(e ->
                e.type() == Event.Type.FAULT && e.detail().contains("тугой ход")));
    }

    @Test
    void injectedHeatingFault_marksOvenAndShiftsReadiness() {
        var sim = new Simulator(cfg(0));
        sim.injectFailure(Simulator.FailureType.OVEN_HEATING);

        assertEquals(1, sim.ovenFaults());
        assertEquals(OvenState.FAULT, sim.state(1));
        assertEquals(30, sim.readyAt(1), 1e-9, "ремонт обогрева 30 мин с текущего момента");
        assertTrue(sim.events().stream().anyMatch(e ->
                e.type() == Event.Type.FAULT && e.ovenNo() == 1));
    }

    @Test
    void machineStatus_reportsRunningAndIdle() {
        var sim = new Simulator(cfg(0));
        sim.advance(13);
        var kv = byCode(sim, "КВ");
        assertTrue(kv.runningAt(sim.simMinutes()), "КВ ещё выталкивает: " + kv);

        sim.advance(17); // t = 30, следующая выдача ещё не началась
        var kvLater = byCode(sim, "КВ");
        assertFalse(kvLater.runningAt(sim.simMinutes()), "КВ ждёт следующую печь: " + kvLater);
    }

    @Test
    void events_areChronological() {
        var sim = new Simulator(SimConfig.defaults());
        sim.advance(24 * 60);
        var ev = sim.events();
        assertFalse(ev.isEmpty());
        for (int i = 1; i < ev.size(); i++)
            assertTrue(ev.get(i).simMin() >= ev.get(i - 1).simMin(),
                    "событие %d раньше предыдущего".formatted(i));
    }

    private static Simulator.MachineStatus byCode(Simulator sim, String code) {
        return sim.machineStatus().stream()
                .filter(s -> s.code().equals(code))
                .findFirst().orElseThrow();
    }
}
