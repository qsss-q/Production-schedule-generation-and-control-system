package org.example.coke_factory.service;

import org.example.coke_factory.service.SimulatorService.ConfigRequest;
import org.example.coke_factory.service.SimulatorService.ControlRequest;
import org.example.coke_factory.service.SimulatorService.FailureRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        service.configure(new ConfigRequest(30, null, null, null));
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
    void changingSeedRebuildsSimulator() {
        service.control(new ControlRequest("step", 100L));
        service.configure(new ConfigRequest(null, 777L, null, null));
        assertThat(service.state().simMinutes()).isZero();
        assertThat(service.state().seed()).isEqualTo(777L);
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

}
