package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Выгрузка и загрузка состояния батареи: по снимку симуляция продолжается ровно с того же места. */
class SimulatorStateTest {

    /** Отказы частые, чтобы поток случайных чисел и занятость машин были существенными. */
    private static SimConfig busyCfg(long seed) {
        return new SimConfig(69, "9-2", 840, 830, 5,
                1.26, 2.0, 1.0, 1.0, 0.15, 60,
                1.0, 2.0, 5.0, 40.0,
                0.05, 30, seed);
    }

    private static Simulator runTo(SimConfig cfg, long minutes) {
        var sim = new Simulator(cfg);
        sim.advance(minutes);
        return sim;
    }

    private static void assertSameState(Simulator expected, Simulator actual) {
        assertEquals(expected.simMinutes(), actual.simMinutes());
        assertEquals(expected.nextIssue(), actual.nextIssue());
        assertEquals(expected.lastDischargeOven(), actual.lastDischargeOven());
        assertEquals(expected.events(), actual.events(), "журнал событий");
        assertEquals(expected.dischargeHistory(), actual.dischargeHistory(), "история выдач");
        assertEquals(expected.machineStatus(), actual.machineStatus(), "машины: позиция, занятость, загрузка");
        assertEquals(expected.discharges(), actual.discharges());
        assertEquals(expected.lateCount(), actual.lateCount());
        assertEquals(expected.overholdCount(), actual.overholdCount());
        assertEquals(expected.pushJams(), actual.pushJams());
        assertEquals(expected.ovenFaults(), actual.ovenFaults());
        assertArrayEquals(expected.plan(), actual.plan(), 0.0, "план выдач");
        assertEquals(expected.planStart(), actual.planStart());
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.skipped(), actual.skipped());
        assertEquals(expected.forecast(69), actual.forecast(69), "прогноз выдач");
        for (int p = 1; p <= expected.ovenCount(); p++) {
            assertEquals(expected.state(p), actual.state(p), "состояние печи " + p);
            assertEquals(expected.readyAt(p), actual.readyAt(p), 0.0, "готовность печи " + p);
            assertEquals(expected.loadDoneAt(p), actual.loadDoneAt(p), 0.0, "загрузка печи " + p);
            assertEquals(expected.dischargeAt(p), actual.dischargeAt(p), 0.0, "выдача печи " + p);
            assertEquals(expected.ovenAvailable(p), actual.ovenAvailable(p), "доступность печи " + p);
        }
    }

    @Test
    void roundTrip_continuesIdentically_includingRandomFailures() {
        var original = runTo(busyCfg(7), 500);
        original.injectFailure(Simulator.FailureType.PUSH_JAM);   // несработавший отказ тоже сохраняется
        assertTrue(original.pushJams() > 0, "в исходном прогоне уже были тугие ходы");
        assertTrue(original.ovenFaults() > 0, "в исходном прогоне уже были отказы обогрева");

        var restored = Simulator.restore(original.exportState());
        assertSameState(original, restored);

        original.advance(1500);
        restored.advance(1500);
        assertSameState(original, restored);
    }

    @Test
    void roundTrip_equalsUninterruptedRun() {
        var whole = runTo(SimConfig.defaults(), 2000);

        var half = runTo(SimConfig.defaults(), 700);
        var resumed = Simulator.restore(half.exportState());
        resumed.advance(1300);

        assertSameState(whole, resumed);
    }

    @Test
    void exportedSnapshot_doesNotChangeWithSimulator() {
        var sim = runTo(busyCfg(11), 300);
        var snapshot = sim.exportState();
        int eventsAtExport = snapshot.events().size();

        sim.advance(600);

        assertEquals(300, snapshot.simMin());
        assertEquals(eventsAtExport, snapshot.events().size());
        assertEquals(sim.config(), snapshot.config());
    }

    @Test
    void snapshot_listsEveryOvenAndMachine() {
        var sim = runTo(SimConfig.defaults(), 60);
        var snapshot = sim.exportState();

        assertEquals(2, snapshot.formatVersion());
        assertEquals(69, snapshot.ovens().size());
        for (int p = 1; p <= 69; p++) {
            assertEquals(p, snapshot.ovens().get(p - 1).number());
            assertEquals(sim.state(p).name(), snapshot.ovens().get(p - 1).state());
        }
        assertEquals(List.of("DOOR_MACHINE", "PUSHING", "QUENCH_CAR", "LOADING_CAR"),
                snapshot.machines().stream().map(Machine.Snapshot::kind).toList());
        assertEquals(sim.discharges(), snapshot.discharges());
        assertEquals(sim.dischargeHistory().size(), snapshot.dischargeHistory().size());
    }

    @Test
    void restore_appliesEditedSnapshot() {
        var sim = runTo(SimConfig.defaults(), 60);
        var s = sim.exportState();
        assertEquals(OvenState.COKING, sim.state(35));

        // В файле печь 35 помечена стоящей в ремонте до 200-й минуты
        var edited = with(s, b -> {
            List<Simulator.OvenSnapshot> ovens = new ArrayList<>(b.ovens);
            var o = ovens.get(34);
            ovens.set(34, new Simulator.OvenSnapshot(o.number(), o.state(), o.readyAtMin(),
                    200.0, o.nextHeatFaultAtMin(), o.dischargeAtMin(), o.loadDoneAtMin()));
            b.ovens = ovens;
        });

        var restored = Simulator.restore(edited);
        assertEquals(OvenState.FAULT, restored.state(35));
        restored.advance(150);                                 // ремонт закончился
        assertEquals(OvenState.COKING, restored.state(35));
        assertEquals(OvenState.COKING, sim.state(35), "исходный симулятор не затронут");
    }

    @Test
    void restore_appliesEditedMachineAndPendingJam() {
        var s = runTo(SimConfig.defaults(), 60).exportState();
        assertEquals(0, s.pushJams());

        // В файле: КВ занят до 150-й минуты и на ближайшую выдачу подложен тугой ход
        var edited = with(s, b -> {
            b.forcedPushJam = true;
            List<Machine.Snapshot> machines = new ArrayList<>(b.machines);
            var pusher = machines.get(1);
            machines.set(1, new Machine.Snapshot(pusher.kind(), pusher.pos(), 150.0,
                    0.0, pusher.tasksDone(), pusher.busyMin(), pusher.busyPeriods()));
            b.machines = machines;
        });

        var restored = Simulator.restore(edited);
        assertTrue(restored.forecast(1).get(0).predictedStartMin() >= 150.0,
                "следующая выдача ждёт занятый выталкиватель");

        restored.advance(95);                                  // t = 155
        assertEquals(1, restored.pushJams(), "подложенный тугой ход сработал");
        assertTrue(restored.events().stream().anyMatch(e ->
                e.type() == Event.Type.FAULT && e.detail().contains("тугой ход")));
    }

    @Test
    void restore_rejectsInconsistentSnapshots() {
        var s = runTo(SimConfig.defaults(), 60).exportState();

        assertThrows(IllegalArgumentException.class, () -> Simulator.restore(null),
                "пустой снимок");
        // версия 1 больше не поддерживается, версия из будущего — тоже
        assertThrows(IllegalArgumentException.class,
                () -> Simulator.restore(with(s, b -> b.formatVersion = 1)));
        assertThrows(IllegalArgumentException.class,
                () -> Simulator.restore(with(s, b -> b.formatVersion = 99)));
        assertThrows(IllegalArgumentException.class,
                () -> Simulator.restore(with(s, b -> b.simMin = -1)));

        // печей на одну меньше, чем в конфигурации батареи
        assertThrows(IllegalArgumentException.class,
                () -> Simulator.restore(with(s, b -> b.ovens = s.ovens().subList(0, s.ovens().size() - 1))));

        // машин три вместо четырёх
        assertThrows(IllegalArgumentException.class,
                () -> Simulator.restore(with(s, b -> b.machines = s.machines().subList(0, 3))));

        // ДСМ и КВ поменяны местами: снимок не того типа машины
        assertThrows(IllegalArgumentException.class, () -> Simulator.restore(with(s, b -> {
            List<Machine.Snapshot> machines = new ArrayList<>(b.machines);
            var door = machines.get(0);
            machines.set(0, machines.get(1));
            machines.set(1, door);
            b.machines = machines;
        })));

        // у печи 5 время готовности — не число
        assertThrows(IllegalArgumentException.class, () -> Simulator.restore(with(s, b -> {
            List<Simulator.OvenSnapshot> ovens = new ArrayList<>(b.ovens);
            var o = ovens.get(4);
            ovens.set(4, new Simulator.OvenSnapshot(o.number(), o.state(), Double.NaN,
                    o.heatRecoverAtMin(), o.nextHeatFaultAtMin(), o.dischargeAtMin(), o.loadDoneAtMin()));
            b.ovens = ovens;
        })));
    }

    @Test
    void roundTrip_keepsPlanMachineDownAndWithdrawnOven() {
        var original = runTo(busyCfg(7), 200);
        double[] plan = new double[10];
        for (int i = 0; i < plan.length; i++) plan[i] = original.simMinutes() + 5 + i * 15;
        original.setPlan(plan);
        original.setMachineDown(MachineKind.QUENCH_CAR, 90);
        original.setOvenAvailable(5, false, "ремонт кладки");

        var restored = Simulator.restore(original.exportState());
        assertSameState(original, restored);
        assertArrayEquals(original.plan(), restored.plan(), 0.0, "план выдач");
        assertEquals(original.planStart(), restored.planStart());
        assertFalse(restored.ovenAvailable(5), "выведенная печь восстановлена");
        assertTrue(restored.machineDownAt(MachineKind.QUENCH_CAR, restored.simMinutes()));

        original.advance(600);
        restored.advance(600);
        assertSameState(original, restored);
    }

    /** Копия снимка с правками: record неизменяемый, поэтому пересобираем целиком. */
    private static Simulator.BatteryState with(Simulator.BatteryState s, Consumer<Builder> edit) {
        var b = new Builder(s);
        edit.accept(b);
        return new Simulator.BatteryState(b.formatVersion, s.config(), b.simMin, s.nextIssue(),
                s.lastDischargeOven(), s.discharges(), s.lateCount(), s.overholdCount(), s.pushJams(),
                s.ovenFaults(), b.forcedPushJam, s.randomDraws(), s.planStart(), b.plan, b.ovenUnavailable,
                s.skipped(), s.revision(), b.ovens, b.machines, s.events(), s.dischargeHistory());
    }

    private static final class Builder {
        int formatVersion;
        long simMin;
        boolean forcedPushJam;
        int planStart;
        double[] plan;
        boolean[] ovenUnavailable;
        List<Simulator.OvenSnapshot> ovens;
        List<Machine.Snapshot> machines;

        Builder(Simulator.BatteryState s) {
            formatVersion = s.formatVersion();
            simMin = s.simMin();
            forcedPushJam = s.forcedPushJam();
            planStart = s.planStart();
            plan = s.planStartsMin();
            ovenUnavailable = s.ovenUnavailable();
            ovens = s.ovens();
            machines = s.machines();
        }
    }
}
