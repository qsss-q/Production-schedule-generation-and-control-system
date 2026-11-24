package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * План как вход симулятора, простой машин и доступность печей:
 * выдача идёт по плану, а не по внутренней сетке k·Δt; порядок печей
 * не меняется ни одной из этих ручек (ограничение H1).
 */
class SimulatorPlanTest {

    private static SimConfig cfg() {
        return new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, 0.0, 60,
                1.0, 2.0, 5.0, 40.0,
                0.0, 30, 42L);
    }

    // --- план как вход ---

    @Test
    void planShiftsDischarges_factFollowsPlan() {
        var sim = new Simulator(cfg());
        sim.advance(13);                                  // первая выдача по сетке, 12,17
        assertEquals(1, sim.discharges());

        double[] plan = new double[8];
        for (int i = 0; i < plan.length; i++) plan[i] = 30 + i * 20;   // позже сетки
        sim.setPlan(plan);
        assertEquals(1, sim.planStart(), "план начинается с текущей выдачи");

        sim.advance(165);                                 // до 178 мин: план и ничего сверх него
        var h = sim.dischargeHistory();
        assertEquals(9, h.size());
        for (int i = 1; i <= 8; i++)
            assertEquals(plan[i - 1], h.get(i).actualMin(), 1e-9,
                    "выдача %d идёт по плану, а не по сетке".formatted(i));
    }

    @Test
    void planEarlierThanGrid_doesNotSpeedUpBeyondReadiness() {
        var sim = new Simulator(cfg());
        double[] plan = {1.0, 2.0, 3.0};                  // раньше готовности печей и слотов
        sim.setPlan(plan);
        sim.advance(60);
        var h = sim.dischargeHistory();
        assertFalse(h.isEmpty());
        for (var r : h)
            assertTrue(r.actualMin() >= r.readyAtMin() - 1e-9,
                    "план не может выдать печь раньше готовности");
    }

    @Test
    void planRejectsNonMonotonicAndNonNumericTimes() {
        var sim = new Simulator(cfg());
        assertThrows(IllegalArgumentException.class, () -> sim.setPlan(new double[]{30, 20}));
        assertThrows(IllegalArgumentException.class, () -> sim.setPlan(new double[]{30, Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> sim.setPlan(new double[]{30, Double.POSITIVE_INFINITY}));
        assertArrayEquals(new double[0], sim.plan(), "отвергнутый план не устанавливается");
    }

    @Test
    void plannedSlot_continuesByGridAfterPlanEnds() {
        var sim = new Simulator(cfg());
        sim.advance(13);                                  // planStart станет 1
        double[] plan = {100, 120, 140};
        sim.setPlan(plan);                                // план для выдач 1, 2, 3
        // до плана — базовая сетка k·Δt
        assertEquals(sim.deltaMin(), sim.plannedSlot(0), 1e-9);
        assertEquals(100, sim.plannedSlot(1), 1e-9);
        assertEquals(120, sim.plannedSlot(2), 1e-9);
        assertEquals(140, sim.plannedSlot(3), 1e-9);
        // за пределами плана выдачи идут по сетке от последнего планового времени
        assertEquals(140 + sim.deltaMin(), sim.plannedSlot(4), 1e-9);
    }

    @Test
    void readinessAndOvensAhead_followSeriality() {
        var sim = new Simulator(cfg());
        assertArrayEquals(new int[]{1, 10, 19}, sim.ovensAhead(3));
        double[] readiness = sim.readinessAhead(3);
        assertEquals(sim.readyAt(1), readiness[0], 1e-9);
        assertEquals(sim.readyAt(10), readiness[1], 1e-9);
        assertEquals(sim.readyAt(19), readiness[2], 1e-9);
    }

    // --- простой машин ---

    @Test
    void machineDown_pusherStallsFlow_thenFlowCatchesUp() {
        var sim = new Simulator(cfg());
        sim.advance(34);                                  // две выдачи по сетке
        assertEquals(2, sim.discharges());

        sim.setMachineDown(MachineKind.PUSHING, 24);      // до 58-й минуты
        assertTrue(sim.machineDownAt(MachineKind.PUSHING, sim.simMinutes()));
        assertTrue(sim.events().stream().anyMatch(e -> e.type() == Event.Type.MACHINE_DOWN));

        sim.advance(60);                                  // t = 94
        var after = sim.dischargeHistory().stream().filter(r -> r.actualMin() > 34).toList();
        assertFalse(after.isEmpty(), "после возврата КВ выдачи возобновились");
        for (var r : after)
            assertTrue(r.actualMin() >= 58 - 1e-9,
                    "выдача %.1f началась во время простоя КВ".formatted(r.actualMin()));
        // очередь догоняет, но кинематика держит нижний предел интервала
        for (int i = 1; i < after.size(); i++)
            assertTrue(after.get(i).actualMin() - after.get(i - 1).actualMin() >= 8.7 - 1e-9,
                    "интервал %.2f мин нарушает предел форсирования".formatted(
                            after.get(i).actualMin() - after.get(i - 1).actualMin()));
    }

    @Test
    void machineDownUntilReturn_blocksFlowUntilMachineUp() {
        var sim = new Simulator(cfg());
        sim.advance(34);
        sim.setMachineDown(MachineKind.QUENCH_CAR, 0);    // до возврата в работу

        var tv = sim.machineStatus().get(2);
        assertTrue(tv.down(), "ТВ в простое: " + tv);
        assertEquals(Simulator.FAR, tv.downUntil(), 1e-9);

        sim.advance(120);
        assertEquals(2, sim.discharges(), "в простой до возврата выдачи стоят");

        sim.setMachineUp(MachineKind.QUENCH_CAR);
        assertFalse(sim.machineDownAt(MachineKind.QUENCH_CAR, sim.simMinutes()));
        assertTrue(sim.events().stream().anyMatch(e -> e.type() == Event.Type.MACHINE_UP));

        sim.advance(30);
        assertTrue(sim.discharges() > 2, "после возврата выдачи пошли");
    }

    @Test
    void machineDown_repeatedCallExtends_holdUpOnly() {
        var sim = new Simulator(cfg());
        sim.advance(34);
        sim.setMachineDown(MachineKind.PUSHING, 10);
        long revisionAfterFirst = sim.revision();
        sim.setMachineDown(MachineKind.PUSHING, 20);      // продление — тоже возмущение
        assertTrue(sim.revision() >= revisionAfterFirst);
        assertTrue(sim.machineDownAt(MachineKind.PUSHING, sim.simMinutes() + 15),
                "простой продлён");
    }

    // --- доступность печей ---

    @Test
    void withdrawnOven_isSkipped_othersKeepTheirSlots() {
        var sim = new Simulator(cfg());
        sim.setOvenAvailable(1, false, "ремонт кладки");

        assertEquals(OvenState.UNAVAILABLE, sim.state(1));
        assertFalse(sim.ovenAvailable(1));
        assertTrue(sim.events().stream().anyMatch(e -> e.type() == Event.Type.EXCLUDED && e.ovenNo() == 1));

        sim.advance(30);
        assertTrue(sim.events().stream().anyMatch(e ->
                e.type() == Event.Type.SKIPPED && e.ovenNo() == 1));
        assertEquals(1, sim.skipped());

        // печь 10 выдаётся в свой слот 24,3 — пропуск не сдвигает слоты остальных
        var h = sim.dischargeHistory();
        assertEquals(1, h.size());
        assertEquals(10, h.get(0).ovenNo());
        assertEquals(2 * sim.deltaMin(), h.get(0).actualMin(), 1e-9);

        // прогноз не показывает выведенную печь
        var f = sim.forecast(69);
        assertTrue(f.stream().noneMatch(d -> d.ovenNo() == 1), "выведенная печь в прогнозе");
        assertEquals(68, f.size());
    }

    @Test
    void returnedOven_dischargesOnNextPass() {
        var sim = new Simulator(cfg());
        sim.setOvenAvailable(1, false, null);
        sim.advance(30);
        assertEquals(1, sim.skipped());

        sim.setOvenAvailable(1, true, "ремонт закончен");
        assertTrue(sim.events().stream().anyMatch(e -> e.type() == Event.Type.INCLUDED && e.ovenNo() == 1));
        assertNotEquals(OvenState.UNAVAILABLE, sim.state(1), "печь вернулась в серию");

        sim.advance(900);                                 // следующий проход
        assertTrue(sim.dischargeHistory().stream().anyMatch(r -> r.ovenNo() == 1),
                "печь 1 выдана в следующем проходе");
    }

    @Test
    void setOvenAvailable_repeatedCallIsNoOp() {
        var sim = new Simulator(cfg());
        sim.setOvenAvailable(3, false, null);
        long revisionAfterFirst = sim.revision();
        int eventsBefore = sim.events().size();

        sim.setOvenAvailable(3, false, null);             // повторный вывод — ничего не меняет
        assertEquals(revisionAfterFirst, sim.revision());
        assertEquals(eventsBefore, sim.events().size());

        sim.setOvenAvailable(3, true, null);
        sim.setOvenAvailable(3, true, null);              // повторный возврат — тоже
        assertEquals(revisionAfterFirst + 1, sim.revision());
    }

    @Test
    void revision_countsDisturbances() {
        var sim = new Simulator(cfg());
        sim.advance(13);
        long r0 = sim.revision();

        sim.setOvenAvailable(3, false, null);
        long r1 = sim.revision();
        assertTrue(r1 > r0, "вывод печи — возмущение");

        sim.setMachineDown(MachineKind.PUSHING, 30);
        long r2 = sim.revision();
        assertTrue(r2 > r1, "простой машины — возмущение");

        sim.setMachineUp(MachineKind.PUSHING);
        assertTrue(sim.revision() > r2, "возврат машины — возмущение");
    }
}
