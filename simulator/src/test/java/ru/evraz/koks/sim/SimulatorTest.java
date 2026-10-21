package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Поведение движка: слоты, передержки, отказы, детерминизм, машины. */
class SimulatorTest {

    private static SimConfig cfg(double cokingMin, double pushJamProb) {
        return new SimConfig(69, "9-2", 840, cokingMin, 5,
                1.26, 2.0, 1.0, 1.0, pushJamProb, 60,
                1.0, 2.0, 5.0, 40.0,
                0.0, 30, 42L);
    }

    private static Map<String, Simulator.MachineStatus> byCode(Simulator sim) {
        return sim.machineStatus().stream()
                .collect(Collectors.toMap(Simulator.MachineStatus::code, Function.identity()));
    }

    @Test
    void nominalRun_dischargesEveryDeltaAndNoOverhold() {
        // Без отказов; период коксования 830 → печь готова к слоту с запасом ~3 мин,
        // машины успевают (самая долгая операция — ТВ ~6 мин << Δt = 12.17 мин).
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(24 * 60);
        // За сутки при Δt = 12.17 мин плановых слотов 118
        assertEquals(118, sim.discharges());
        assertEquals(0, sim.pushJams());
        assertEquals(0, sim.ovenFaults());
        assertEquals(0, sim.overholdCount());
        assertEquals(0, sim.lateCount());
    }

    @Test
    void dischargeSequence_followsSerialityOrder() {
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(2 * 60);
        List<Integer> ovenOrder = sim.events().stream()
                .filter(e -> e.type() == Event.Type.DISCHARGE)
                .map(Event::ovenNo)
                .toList();
        // Выдачи следуют порядку серийности: серия 1 — 1, 10, 19, …, 64 (шаг 9)
        assertEquals(1, ovenOrder.get(0));
        assertEquals(10, ovenOrder.get(1));
        assertEquals(64, ovenOrder.get(7));
    }

    @Test
    void whenCokeTooEarly_ovensWaitOverTolerance() {
        // Период коксования короче: печь готова (в стационаре) за 8 мин
        // до слота — ожидание 8 мин > допуска 5, передержка у каждой выдачи.
        var sim = new Simulator(cfg(825, 0.0));
        sim.advance(24 * 60);
        assertEquals(118, sim.overholdCount(),
                "все выдачи должны быть с передержкой");
    }

    @Test
    void pushJam_alwaysFails_slowsFlowAndOverholdsOvens() {
        // Каждая выдача застревает: КВ занята 2+60 мин вместо 2 →
        // фактический интервал выдач ≈ 68 мин вместо 12.17.
        var sim = new Simulator(cfg(830, 1.0));
        sim.advance(24 * 60);
        assertTrue(sim.discharges() < 100, "поток должен замедлиться: " + sim.discharges() + " выдач");
        assertEquals(sim.discharges(), sim.pushJams(), "каждая выдача потребовала ремонт");
        assertTrue(sim.overholdCount() > 0,
                "печи, задержавшиеся из-за занятой машины, должны получить передержку");
        assertTrue(sim.events().stream().anyMatch(e -> e.type() == Event.Type.FAULT));
    }

    @Test
    void doorMachine_becomesBottleneck_whenDoorIsSlow() {
        // Снятие двери 20 мин вместо 1: ДСМ не успевает к слоту,
        // выталкивание сдвигается, печи получают передержку.
        var cfg = new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 20.0, 1.0, 0.0, 60,
                1.0, 2.0, 5.0, 40.0,
                0.0, 30, 42L);
        var sim = new Simulator(cfg);
        sim.advance(24 * 60);
        assertTrue(sim.discharges() < 118, "ДСМ должен стать узким горлышком: " + sim.discharges());
        assertTrue(sim.overholdCount() > 0, "печи ждут ДСМ и передерживаются");
    }

    @Test
    void heatFault_prolongsCoking_andYieldsLate() {
        // интенсифицируем отказ обогрева: 1 отказ в час на печь
        var cfg = new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, 0.0, 60,
                1.0, 2.0, 5.0, 40.0,
                1.0, 30, 7L);
        var sim = new Simulator(cfg);
        sim.advance(24 * 60);
        assertTrue(sim.ovenFaults() > 0);
        assertTrue(sim.lateCount() > 0, "печи с продлённым коксованием не успевают к слоту");
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
    void stateTransition_afterDischargeOvenGoesEmptyThenCoking() {
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(13); // первая выдача начинается в Δt = 12.17 мин
        assertEquals(1, sim.discharges());
        // Печь 1 выдана в 12.17 мин: камера пуста и ждёт загрузку (~19 мин)
        assertEquals(OvenState.EMPTY, sim.state(1));
        // Печь 2 (слот в 24.3 мин) ещё кокосится
        assertEquals(OvenState.COKING, sim.state(2));
    }

    @Test
    void emptyOvenBecomesCokingAfterLoading() {
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(30); // выдача печи 1 в 12.17, загрузка завершена ~19 мин
        // После загрузки коксование: печь 1 не готова до ~849 мин
        assertEquals(OvenState.COKING, sim.state(1));
        assertTrue(sim.events().stream().anyMatch(e ->
                e.type() == Event.Type.LOADED && e.ovenNo() == 1));
    }

    @Test
    void dischargeToLoading_fitsEightMinuteNorm() {
        // Норматив docs/06: выдача → загрузка камеры ≤ 8 мин.
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(30);
        var discharge = sim.events().stream()
                .filter(e -> e.type() == Event.Type.DISCHARGE && e.ovenNo() == 1)
                .findFirst().orElseThrow();
        var loaded = sim.events().stream()
                .filter(e -> e.type() == Event.Type.LOADED && e.ovenNo() == 1)
                .findFirst().orElseThrow();
        assertTrue(loaded.simMin() - discharge.simMin() <= 8,
                "выдача→загрузка %d мин > 8".formatted(loaded.simMin() - discharge.simMin()));
    }

    @Test
    void machines_areTrackedEntities() {
        var sim = new Simulator(SimConfig.defaults());
        sim.advance(24 * 60);
        var status = byCode(sim);
        assertEquals(4, status.size(), "КВ, ДСМ, ТВ и УВ — отдельные машины");

        var kv = status.get("КВ");
        var door = status.get("ДСМ");
        var tv = status.get("ТВ");
        var uv = status.get("УВ");
        assertNotNull(kv);
        assertNotNull(door);
        assertNotNull(tv);
        assertNotNull(uv);

        // Каждая выдача — задача КВ, ТВ и УВ; ДСМ снимает и ставит дверь.
        assertEquals(sim.discharges(), kv.tasksDone(), "КВ обслуживает каждую выдачу: " + kv);
        assertEquals(sim.discharges(), tv.tasksDone(), "ТВ обслуживает каждую выдачу: " + tv);
        assertEquals(sim.discharges(), uv.tasksDone(), "УВ обслуживает каждую выдачу: " + uv);
        assertEquals(2 * sim.discharges(), door.tasksDone(), "ДСМ: снятие + установка: " + door);

        for (var s : status.values()) {
            assertTrue(s.utilization() > 0 && s.utilization() <= 1.0, "занятость некорректна: " + s);
            assertTrue(s.posM() >= 0, "позиция в метрах некорректна: " + s);
        }
        // КВ осталась у печи последней выдачи (не у башни), ТВ — у тушильной башни
        assertTrue(kv.pos() <= 69, "КВ должна быть у печи: " + kv);
        assertTrue(tv.pos() > 69, "ТВ после разгрузки у башни: " + tv);
    }

    @Test
    void quenchCar_becomesBottleneck_whenUnloadIsSlow() {
        // Разгрузка 11 мин вместо 2: ТВ занят ~16 мин на выдачу > Δt = 12.17 →
        // узкое горлышко, поток замедляется.
        var cfg = new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, 0.0, 60,
                1.0, 11.0, 5.0, 40.0,
                0.0, 30, 42L);
        var sim = new Simulator(cfg);
        sim.advance(24 * 60);
        assertTrue(sim.discharges() < 118, "ТВ должен стать узким горлышком: " + sim.discharges());
        assertTrue(sim.overholdCount() > 0, "печи ждут занятый ТВ и передерживаются");
    }
}
