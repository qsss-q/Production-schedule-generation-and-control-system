package ru.evraz.koks.sim.planner;

import ru.evraz.koks.sim.Event;
import ru.evraz.koks.sim.MachineKind;
import ru.evraz.koks.sim.Simulator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Метрики сравнения прогонов M1–M9 (docs/05 §6). Считаются по факту одного
 * окна модельного времени: одна и та же точка старта, один и тот же сценарий
 * отказов — тогда прогоны сопоставимы.
 *
 * <p>Трактовки, которых нет в docs/05 (зафиксированы здесь):
 * <ul>
 *   <li>ОЕЭ печи = T_кокс / фактический средний оборот печи в окне: чем ровнее
 *       и плотнее поток, тем ближе ОЕЭ печей друг к другу. Печи с одной выдачей
 *       в окне не участвуют — оборот не измерить;</li>
 *   <li>простой машин = окно мин занятость машин (переезды + операции);
 *       перестой печей = Σ (факт − готовность) по выдачам;</li>
 *   <li>M6 ограничивается офлайн-проверками H1 (последовательность выдач) и
 *       H4 (выдача → загрузка ≤ 8 мин); остальные ограничения живут в
 *       симуляторе физикой;</li>
 *   <li>К_расч = (м − а)/м, где а — плановые слоты, назначенные раньше
 *       готовности печи более чем на допуск: план обещает печь, которая
 *       не успевает, — это плановое отклонение периода коксования.</li>
 * </ul>
 */
public final class Metrics {

    /** Норматив «от выдачи до загрузки шихты» (docs/05 §1.1, H4), мин. */
    public static final double DISCHARGE_TO_LOAD_MAX = 8.0;

    /**
     * @param totalDischarges  операций выдачи в окне
     * @param lateCount        M1: просрочено, факт позже плана более чем на допуск
     * @param totalDelayMin    M2: суммарная просрочка, мин
     * @param oeeUniformityPct M3: 100·min(ОЕЭ)/max(ОЕЭ); NaN — печей с измеримым оборотом меньше двух
     * @param oeeSpreadPct     M4: max(ОЕЭ) − min(ОЕЭ), п.п.; NaN там же, где M3
     * @param machineIdleMin   M5: суммарный простой машин в окне, мин
     * @param ovenWaitMin      M5: суммарный перестой готовых печей, мин
     * @param violations       M6: нарушения H1 и H4 в окне
     * @param calcUniformity   M7: К_расч = (м − а)/м; NaN — выдач не было
     * @param intervalStdMin   M8: СКО интервалов между выдачами, мин; NaN — интервалов меньше двух
     * @param maxOverholdMin   M9: максимальная передержка (факт − готовность), мин
     */
    public record Result(int totalDischarges, int lateCount, double totalDelayMin,
                         double oeeUniformityPct, double oeeSpreadPct,
                         double machineIdleMin, double ovenWaitMin,
                         int violations, double calcUniformity,
                         double intervalStdMin, double maxOverholdMin) { }

    /**
     * Метрики окна [fromMin, toMin] прогона.
     *
     * @param toleranceMin допуск опоздания/передержки (норма 5 мин)
     * @param cokingMin    нормативный период коксования, мин — знаменатель ОЕЭ
     * @param loadMin      норматив загрузки камеры — для проверки H4
     */
    public static Result compute(Simulator sim, double fromMin, double toMin,
                                 double toleranceMin, double cokingMin, double loadMin) {
        List<Simulator.DischargeRecord> history = sim.dischargeHistory().stream()
                .filter(r -> r.actualMin() >= fromMin - 1e-9 && r.actualMin() <= toMin + 1e-9)
                .toList();
        int total = history.size();

        // M1, M2, M5 (перестой печей), M9, M7
        int late = 0;
        double totalDelay = 0;
        double ovenWait = 0;
        double maxOverhold = 0;
        int plannedBeforeReady = 0;
        for (Simulator.DischargeRecord r : history) {
            double delay = r.actualMin() - r.plannedMin();
            if (delay > toleranceMin + 1e-9) late++;
            if (delay > 0) totalDelay += delay;
            double over = r.actualMin() - r.readyAtMin();
            if (over > 0) ovenWait += over;
            if (over > maxOverhold) maxOverhold = over;
            if (r.plannedMin() < r.readyAtMin() - toleranceMin - 1e-9) plannedBeforeReady++;
        }

        // M5: простой машин — окно мин занятость
        double window = Math.max(0, toMin - fromMin);
        double machineIdle = 0;
        for (MachineKind kind : MachineKind.values())
            machineIdle += Math.max(0, window - sim.machineBusyMin(kind, fromMin, toMin));

        // M3, M4: ОЕЭ по печам с измеримым оборотом
        Map<Integer, Double> lastActual = new HashMap<>();
        Map<Integer, Double> sumCycle = new HashMap<>();
        Map<Integer, Integer> cycleCount = new HashMap<>();
        for (Simulator.DischargeRecord r : history) {
            Double prev = lastActual.get(r.ovenNo());
            if (prev != null) {
                double cycle = r.actualMin() - prev;
                if (cycle > 0) {
                    sumCycle.merge(r.ovenNo(), cycle, Double::sum);
                    cycleCount.merge(r.ovenNo(), 1, Integer::sum);
                }
            }
            lastActual.put(r.ovenNo(), r.actualMin());
        }
        double minOee = Double.POSITIVE_INFINITY;
        double maxOee = Double.NEGATIVE_INFINITY;
        for (var e : cycleCount.entrySet()) {
            double oee = cokingMin / (sumCycle.get(e.getKey()) / e.getValue());
            minOee = Math.min(minOee, oee);
            maxOee = Math.max(maxOee, oee);
        }
        boolean oeeMeasurable = cycleCount.size() >= 2
                && Double.isFinite(minOee) && Double.isFinite(maxOee) && maxOee > 0;
        double uniformity = oeeMeasurable ? 100.0 * minOee / maxOee : Double.NaN;
        double spread = oeeMeasurable ? 100.0 * (maxOee - minOee) / maxOee : Double.NaN;

        // M6: H1 — последовательность выдач следует порядку серийности;
        // H4 — выдача→загрузка (при тугом ходе камера открыта дольше нормы —
        // это честное нарушение, одинаковое для всех алгоритмов)
        int violations = serialityViolations(sim, history) + dischargeToLoadViolations(sim, fromMin, toMin, loadMin);

        // M8: СКО интервалов между выдачами
        double std = Double.NaN;
        if (history.size() >= 3) {
            double mean = (history.get(history.size() - 1).actualMin() - history.get(0).actualMin())
                    / (history.size() - 1);
            double sq = 0;
            for (int i = 1; i < history.size(); i++) {
                double interval = history.get(i).actualMin() - history.get(i - 1).actualMin();
                sq += (interval - mean) * (interval - mean);
            }
            std = Math.sqrt(sq / (history.size() - 1));
        }

        double calcUniformity = total == 0 ? Double.NaN : (double) (total - plannedBeforeReady) / total;
        return new Result(total, late, totalDelay, uniformity, spread,
                machineIdle, ovenWait, violations, calcUniformity, std, maxOverhold);
    }

    /**
     * H1: последовательность выдач — подпоследовательность циклического порядка
     * серийности. Печь, пропущенная как выведенная из серии, должна совпасть
     * в одном из следующих оборотов; если за полный цикл ожидаемая печь не
     * встретилась — порядок нарушен.
     */
    private static int serialityViolations(Simulator sim, List<Simulator.DischargeRecord> history) {
        if (history.size() < 2) return 0;
        int[] order = sim.order();
        int start = -1;
        for (int i = 0; i < order.length && start < 0; i++)
            if (order[i] == history.get(0).ovenNo()) start = i;
        if (start < 0) return history.size() - 1;           // первой выдачи нет в порядке вовсе

        int violations = 0;
        int next = 1;
        int i = start + 1;
        int walked = 0;
        while (next < history.size() && walked < order.length) {
            if (order[i % order.length] == history.get(next).ovenNo()) {
                next++;
                walked = 0;
            } else {
                walked++;                                   // печь пропущена — ждём её в следующем проходе
            }
            i++;
        }
        if (next < history.size()) violations += history.size() - next;
        return violations;
    }

    /** H4: от выдачи до загрузки шихты ≤ 8 мин (загрузка начинается с конца выталкивания). */
    private static int dischargeToLoadViolations(Simulator sim, double fromMin, double toMin, double loadMin) {
        int violations = 0;
        Map<Integer, Long> lastDischarge = new HashMap<>();
        for (Event e : sim.events()) {
            if (e.type() == Event.Type.DISCHARGE) {
                lastDischarge.put(e.ovenNo(), e.simMin());
            } else if (e.type() == Event.Type.LOADED && e.simMin() >= fromMin - 1e-9
                    && e.simMin() <= toMin + 1e-9) {
                Long discharge = lastDischarge.get(e.ovenNo());
                if (discharge != null && (e.simMin() - loadMin) - discharge > DISCHARGE_TO_LOAD_MAX + 1e-9)
                    violations++;
            }
        }
        return violations;
    }

    private Metrics() { }
}
