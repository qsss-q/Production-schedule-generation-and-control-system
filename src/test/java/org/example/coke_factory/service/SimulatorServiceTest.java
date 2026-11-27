package org.example.coke_factory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.coke_factory.service.SimulatorService.ConfigRequest;
import org.example.coke_factory.service.SimulatorService.ControlRequest;
import org.example.coke_factory.service.SimulatorService.FailureRequest;
import org.example.coke_factory.service.SimulatorService.StateDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.evraz.koks.sim.Simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Тесты обёртки над симулятором. Без Spring и без БД: сервис создаётся напрямую. */
class SimulatorServiceTest {

    private SimulatorService service;

    @BeforeEach
    void setUp() {
        service = new SimulatorService();
    }

    @Test
    void startsPausedWith69Ovens() {
        assertThat(service.ovens()).hasSize(69);
        assertThat(service.state().running()).isFalse();
        assertThat(service.state().simMinutes()).isZero();
    }

    @Test
    void tickDoesNothingOnPause() {
        service.tick();
        assertThat(service.state().simMinutes()).isZero();
    }

    @Test
    void speedX60MeansOneSimMinutePerTick() {
        service.control(new ControlRequest("start", null));
        for (int i = 0; i < 30; i++) {
            service.tick();
        }
        assertThat(service.state().simMinutes()).isEqualTo(30);
    }

    @Test
    void speedX30MeansHalfMinutePerTick() {
        service.configure(new ConfigRequest(30, null, null, null, null));
        service.control(new ControlRequest("start", null));
        for (int i = 0; i < 10; i++) {
            service.tick();
        }
        assertThat(service.state().simMinutes()).isEqualTo(5);
    }

    @Test
    void stepPausesAndAdvances() {
        service.control(new ControlRequest("start", null));
        service.control(new ControlRequest("step", 120L));
        assertThat(service.state().running()).isFalse();
        assertThat(service.state().simMinutes()).isEqualTo(120);
        assertThat(service.feed(20).items()).hasSize(20);
    }

    @Test
    void resetClearsTimeAndCounters() {
        service.control(new ControlRequest("step", 300L));
        service.control(new ControlRequest("reset", null));
        assertThat(service.state().simMinutes()).isZero();
        assertThat(service.state().counters().discharges()).isZero();
    }

    @Test
    void pushJamFailureIsCounted() {
        service.control(new ControlRequest("step", 60L));
        service.inject(new FailureRequest("pushJam", null));
        service.control(new ControlRequest("step", 60L));
        assertThat(service.state().counters().pushJams()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ovenHeatingFailurePutsOvenIntoFault() {
        service.inject(new FailureRequest("ovenHeating", 5));
        assertThat(service.ovens().get(4).state()).isEqualTo("fault");
    }

    @Test
    void applyingUnchangedConfigurationDoesNotResetSimulator() {
        service.control(new ControlRequest("step", 100L));
        long generation = service.generation();
        long seed = service.state().seed();
        service.configure(new ConfigRequest(null, seed, null, null, service.seriality()));
        assertThat(service.state().simMinutes()).isEqualTo(100);
        assertThat(service.generation()).isEqualTo(generation);
    }

    @Test
    void changingSeedRebuildsSimulator() {
        service.control(new ControlRequest("step", 100L));
        service.configure(new ConfigRequest(null, 777L, null, null, null));
        assertThat(service.state().simMinutes()).isZero();
        assertThat(service.state().seed()).isEqualTo(777L);
    }

    @Test
    void changingSerialityRebuildsSimulator() {
        assertThat(service.battery().serialityScheme()).isEqualTo("9-2");
        service.configure(new ConfigRequest(null, null, null, null, "5-2"));
        assertThat(service.battery().serialityScheme()).isEqualTo("5-2");
        // порядок выдачи строится по новой схеме: в серии шаг 5
        assertThat(service.state().nextOvenNumber()).isEqualTo(1);
        assertThatThrownBy(() -> service.configure(new ConfigRequest(null, null, null, null, "9-3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.battery().serialityScheme()).isEqualTo("5-2");
    }

    @Test
    void unknownActionIsRejected() {
        assertThatThrownBy(() -> service.control(new ControlRequest("fly", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventsFromTheFutureAreHidden() {
        service.control(new ControlRequest("step", 200L));
        long now = service.state().simMinutes();
        assertThat(service.events(500)).allMatch(e -> e.simMinutes() <= now);
    }
    @Test
    void snapshotContainsSameModelTimeForOvensAndEvents() {
        service.control(new ControlRequest("step", 73L));
        var snapshot = service.snapshot();
        assertThat(snapshot.state().simTime()).isEqualTo(snapshot.feed().generatedAt());
        assertThat(snapshot.state().simTime()).isEqualTo(snapshot.analytics().generatedAt());
        assertThat(snapshot.ovens()).hasSize(snapshot.battery().ovenCount());
        assertThat(snapshot.events()).allMatch(e -> e.simMinutes() <= snapshot.state().simMinutes());
        for (var oven : snapshot.ovens()) {
            if ("ready".equals(oven.state()) || "overhold".equals(oven.state())) {
                assertThat(oven.progressPct()).isEqualTo(100.0);
            } else if ("coking".equals(oven.state())) {
                double s = Instant.parse(oven.cokingStartedAt()).getEpochSecond();
                double r = Instant.parse(oven.readyAt()).getEpochSecond();
                double n = Instant.parse(snapshot.state().simTime()).getEpochSecond();
                assertThat(oven.progressPct()).isCloseTo(100*(n-s)/(r-s), within(0.11));
            }
        }
    }

    @Test
    void progressChangesWithSimulationTime() {
        double initial = service.ovens().get(50).progressPct();
        service.control(new ControlRequest("step", 10L));
        assertThat(service.ovens().get(50).progressPct()).isGreaterThan(initial);
    }

    @Test
    void ovenProgressCanBeReconstructedFromLiveTimeAndStartTimestamp() {
        // Поле начала коксования позволяет UI получить точно тот же процент,
        // даже если исходный progressPct попал в HTTP-кэш.
        service.control(new ControlRequest("step", 60L));
        var oven = service.ovens().stream()
                .filter(o -> "coking".equals(o.state()))
                .findFirst().orElseThrow();
        double start = Instant.parse(oven.cokingStartedAt()).getEpochSecond();
        double ready = Instant.parse(oven.readyAt()).getEpochSecond();
        double now = Instant.parse(service.state().simTime()).getEpochSecond();
        double computed = 100.0 * (now - start) / (ready - start);
        assertThat(computed).isCloseTo(oven.progressPct(), within(0.11));
    }

    @Test
    void analyticsContainsOnlyCompletedOperationsAndResets() {
        assertThat(service.analytics(20).totalOperations()).isZero();
        assertThat(service.analytics(20).onTimePct()).isNull();

        service.control(new ControlRequest("step", 150L));
        var report = service.analytics(5);
        assertThat(report.totalOperations()).isGreaterThan(2);
        assertThat(report.totalOperations()).isEqualTo(
                report.onTimeOperations() + report.delayedOperations());
        assertThat(report.recentOperations()).hasSize(5);
        assertThat(report.avgIntervalMin()).isNotNull();
        assertThat(report.machineUtilization())
                .allMatch(m -> m.utilizationPct() >= 0 && m.utilizationPct() <= 100);

        service.control(new ControlRequest("reset", null));
        assertThat(service.analytics(20).totalOperations()).isZero();
    }

    @Test
    void jamProducesRecordedDelaysInActualOperations() {
        service.control(new ControlRequest("step", 60L));
        service.inject(new FailureRequest("pushJam", null));
        service.control(new ControlRequest("step", 180L));
        assertThat(service.analytics(20).delayedOperations()).isGreaterThan(0);
        assertThat(service.analytics(20).maxDelayMin()).isGreaterThan(5);
    }

    @Test
    void quenchCarStartsByTowerAndStaysWithinPhysicalCoordinates() {
        var wagon = service.machines().stream()
                .filter(m -> "quenchCar".equals(m.type())).findFirst().orElseThrow();
        assertThat(wagon.location()).isEqualTo("quenchTower");
        assertThat(wagon.pos()).isGreaterThan(69);

        for (int i = 0; i < 120; i++) {
            service.control(new ControlRequest("step", 1L));
            wagon = service.machines().stream()
                    .filter(m -> "quenchCar".equals(m.type())).findFirst().orElseThrow();
            assertThat(wagon.pos()).isBetween(1.0, 110.0);
        }
    }

    @Test
    void exportedStateSurvivesJsonFileAndRestoresTheRun() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        service.control(new ControlRequest("step", 480L));
        Simulator.BatteryState snapshot = service.exportState();
        // файл — это JSON: его могли сохранить, открыть и отредактировать вручную
        Simulator.BatteryState fromFile = mapper.readValue(
                mapper.writeValueAsBytes(snapshot), Simulator.BatteryState.class);

        service.control(new ControlRequest("step", 300L));          // успели уйти дальше
        assertThat(service.state().simMinutes()).isEqualTo(780);
        StateDto after = service.importState(fromFile);

        assertThat(after.simMinutes()).isEqualTo(480);
        assertThat(after.running()).isFalse();
        assertThat(mapper.writeValueAsString(service.exportState()))
                .isEqualTo(mapper.writeValueAsString(snapshot));

        // продолжение после загрузки такое же, как если бы сохранения не было
        SimulatorService uninterrupted = new SimulatorService();
        uninterrupted.control(new ControlRequest("step", 480L));
        uninterrupted.control(new ControlRequest("step", 240L));
        service.control(new ControlRequest("step", 240L));
        assertThat(service.events(200)).isEqualTo(uninterrupted.events(200));
        assertThat(service.ovens()).isEqualTo(uninterrupted.ovens());
    }

    @Test
    void importReplacesConfigurationWithTheOneFromState() {
        SimulatorService other = new SimulatorService();
        other.configure(new ConfigRequest(null, 777L, 0.02, 0.003, null));
        other.control(new ControlRequest("step", 120L));

        StateDto after = service.importState(other.exportState());

        assertThat(after.seed()).isEqualTo(777L);
        assertThat(after.pushJamProb()).isEqualTo(0.02);
        assertThat(service.battery()).isEqualTo(other.battery());
    }

    @Test
    void brokenStateFileIsRejectedAndKeepsCurrentRun() {
        service.control(new ControlRequest("step", 240L));
        Simulator.BatteryState snapshot = service.exportState();
        var withoutOvens = new Simulator.BatteryState(snapshot.formatVersion(), snapshot.config(),
                snapshot.simMin(), snapshot.nextIssue(), snapshot.lastDischargeOven(), snapshot.discharges(),
                snapshot.lateCount(), snapshot.overholdCount(), snapshot.pushJams(), snapshot.ovenFaults(),
                snapshot.forcedPushJam(), snapshot.randomDraws(),
                snapshot.planStart(), snapshot.planStartsMin(), snapshot.ovenUnavailable(),
                snapshot.skipped(), snapshot.revision(),
                List.of(), snapshot.machines(), snapshot.events(), snapshot.dischargeHistory());

        assertThatThrownBy(() -> service.importState(withoutOvens))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("печей");
        assertThat(service.state().simMinutes()).isEqualTo(240);
        assertThat(service.state().counters().discharges()).isEqualTo(snapshot.discharges());

        assertThatThrownBy(() -> service.importState(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
