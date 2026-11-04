package ru.evraz.koks.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Построение серийности S–K по docs/05 §2.
 *
 * Трактовка по документации: первая цифра S — число серий (она же — шаг
 * нумерации печей внутри серии); вторая цифра K — минимальный интервал
 * между выдачами печей одной серии, в шагах Δt выдачи (ограничение 2.4).
 *
 * Печь верхней серии i содержит печи i, i+S, i+2S, … ≤ N, выдача идёт
 * сериями 1, 2, … S подряд.
 */
public final class Seriality {

    /** Разобранная схема "9-2" → series=9, intervalSteps=2. */
    public record Scheme(int series, int intervalSteps) {
        public Scheme {
            if (series < 2)
                throw new IllegalArgumentException("Число серий S должно быть ≥ 2, получено: " + series);
            if (intervalSteps < 1)
                throw new IllegalArgumentException("Интервал K должен быть ≥ 1, получено: " + intervalSteps);
            // Рабочие схемы: 9-2, 5-2, 2-1 — во всех S ≥ K. Иначе ограничение 2.4 нарушено.
            if (series < intervalSteps)
                throw new IllegalArgumentException(
                        "Нерабочая схема S-K: шаг серийности S=%d меньше интервала K=%d".formatted(series, intervalSteps));
        }

        public static Scheme parse(String code) {
            var norm = code.replace("–", "-").replace("—", "-").strip();
            var parts = norm.split("-");
            if (parts.length != 2)
                throw new IllegalArgumentException("Формат серийности S-K (пример: 9-2), получено: " + code);
            return new Scheme(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        @Override
        public String toString() { return series + "-" + intervalSteps; }
    }

    /**
     * Порядок выдачи за один оборот: серия 1 (печи 1, 1+S, …), затем серия 2 и т.д.
     *
     * @param ovenCount число печей в батарее
     * @param scheme    разобранная схема
     * @return номера печей в порядке выдачи, суммарно ровно ovenCount номеров
     */
    public static int[] dischargeOrder(int ovenCount, Scheme scheme) {
        if (ovenCount < 2)
            throw new IllegalArgumentException("В батарее должно быть ≥ 2 печей");
        List<Integer> order = new ArrayList<>(ovenCount);
        for (int start = 1; start <= scheme.series(); start++)
            for (int n = start; n <= ovenCount; n += scheme.series())
                order.add(n);
        if (order.size() != ovenCount)
            throw new IllegalStateException("Порядок выдачи не покрывает все печи: %d из %d"
                    .formatted(order.size(), ovenCount));
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Номер серии печи: печь n принадлежит серии ((n-1) mod S) + 1. */
    public static int seriesOf(int ovenNo, Scheme scheme) {
        return ((ovenNo - 1) % scheme.series()) + 1;
    }

    private Seriality() { }
}
