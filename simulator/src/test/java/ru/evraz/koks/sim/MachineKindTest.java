package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Кинематика переездов машин: трапецеидальный профиль и границы. */
class MachineKindTest {

    private static final double PITCH = 1.26;

    @Test
    void samePosition_takesNoTime() {
        for (var kind : MachineKind.values())
            assertEquals(0.0, kind.travelMin(7, 7, PITCH), 1e-9, kind.code);
    }

    @Test
    void travelTimeGrowsWithDistance() {
        double near = MachineKind.PUSHING.travelMin(1, 2, PITCH);
        double far = MachineKind.PUSHING.travelMin(1, 69, PITCH);
        assertTrue(near > 0);
        assertTrue(far > near, "переезд через батарею дольше соседнего");
    }

    @Test
    void longDistance_reachesMarchSpeed() {
        // L = 1000 м: t = L/v + v/a (разгон и торможение по краям).
        double l = 1000;
        double expectedMin = l / (MachineKind.PUSHING.speedMPerMin / 60.0) / 60.0
                + (MachineKind.PUSHING.speedMPerMin / 60.0) / MachineKind.PUSHING.accelMPerSec2 / 60.0;
        assertEquals(expectedMin, MachineKind.PUSHING.travelMin(0, l / PITCH, PITCH), 1e-6);
    }

    @Test
    void shortDistance_doesNotReachMarchSpeed() {
        // L = 1,26 м: t = 2·√(L/a).
        double l = PITCH;
        double expectedMin = 2.0 * Math.sqrt(l / MachineKind.PUSHING.accelMPerSec2) / 60.0;
        assertEquals(expectedMin, MachineKind.PUSHING.travelMin(0, 1, PITCH), 1e-9);
    }

    @Test
    void doorMachine_isSlowerThanLoadingCar() {
        assertTrue(MachineKind.DOOR_MACHINE.travelMin(1, 30, PITCH)
                > MachineKind.LOADING_CAR.travelMin(1, 30, PITCH));
    }
}
