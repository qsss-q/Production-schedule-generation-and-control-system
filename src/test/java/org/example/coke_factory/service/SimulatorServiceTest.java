package org.example.coke_factory.service;

import org.example.coke_factory.service.SimulatorService.ConfigRequest;
import org.example.coke_factory.service.SimulatorService.ControlRequest;
import org.example.coke_factory.service.SimulatorService.FailureRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
