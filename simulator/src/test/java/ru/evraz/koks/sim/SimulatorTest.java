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
        // Выдачи следуют порядку серийности: серия 1 — 1, 10, 19, …, 64 (шаг 9),
        // затем серия 3, а не серия 2.
        assertEquals(1, ovenOrder.get(0));
        assertEquals(10, ovenOrder.get(1));
        assertEquals(64, ovenOrder.get(7));
        assertEquals(3, ovenOrder.get(8));
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

    @Test
    void heatFaultMidCoking_shiftsReadinessByRepairMin() {
        // docs/09 §7: отказ обогрева в середине коксования останавливает нагрев —
        // готовность уезжает на длительность ремонта, а не «до конца ремонта».
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(30);
        int p = 35;                                  // печь в середине коксования
        double readyBefore = sim.readyAt(p);
        sim.injectOvenHeatingFault(p);

        assertEquals(OvenState.FAULT, sim.state(p));
        assertEquals(readyBefore + 30, sim.readyAt(p), 1e-9,
                "нагрев стоит 30 мин: готовность +30");

        sim.advance(40);                             // ремонт закончен
        assertEquals(OvenState.COKING, sim.state(p), "печь вернулась к коксованию");
        assertEquals(readyBefore + 30, sim.readyAt(p), 1e-9);
    }

    @Test
    void singleJam_delay57AndHalf_andRecovery3MinPerCycle() {
        // Замер из docs/09 §7, теперь фиксируется тестом: тугой ход 60 мин →
        // первая задержка ≈ 57,5 мин; печь, принявшая удар, отыгрывает
        // запас батареи (840 − 830 − 2 − 5) по 3 мин за оборот.
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(13);                             // первая выдача прошла
        sim.injectFailure(Simulator.FailureType.PUSH_JAM);
        sim.advance(5L * 840);

        assertEquals(1, sim.pushJams());
        var h = sim.dischargeHistory();
        // тугой ход не сдвигает старт своей выдачи — он занимает КВ на 60 мин дольше
        assertEquals(0, h.get(1).actualMin() - h.get(1).plannedMin(), 1e-9);
        // первый пострадавший — следующая выдача
        assertEquals(57.5, h.get(2).actualMin() - h.get(2).plannedMin(), 0.5,
                "задержка первой жертвы тугого хода");

        int jamOven = h.get(1).ovenNo();
        double prev = Double.NaN;
        int checks = 0;
        for (var r : h) {
            if (r.ovenNo() != jamOven) continue;
            double dev = r.actualMin() - r.plannedMin();
            if (!Double.isNaN(prev) && prev > 1) {
                assertEquals(3.0, prev - dev, 0.5, "восстановление ≈ 3 мин/оборот");
                checks++;
            }
            prev = dev;
        }
        assertTrue(checks >= 3, "должно быть минимум 3 оборота наблюдения: " + checks);
    }

    @Test
    void machineKinematics_respectForcingFloor() {
        // docs/05 §9: форсированный интервал не может быть меньше 60/(4,9 + 2) ≈ 8,7 мин.
        // Кинематика машин сама держит пол: замер по фактическим выдачам.
        var sim = new Simulator(cfg(830, 0.0));
        sim.advance(13);
        sim.injectFailure(Simulator.FailureType.PUSH_JAM);
        sim.advance(840);

        var h = sim.dischargeHistory();
        for (int i = 2; i < h.size(); i++) {
            double interval = h.get(i).actualMin() - h.get(i - 1).actualMin();
            assertTrue(interval >= 8.7 - 1e-9,
                    "интервал %.2f мин нарушает предел форсирования 8,7 мин".formatted(interval));
        }
    }
}
