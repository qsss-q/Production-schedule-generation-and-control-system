package ru.evraz.koks.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Построение серийности S–K по docs/05 §2.
 *
 * <p>Первая цифра S — число серий и шаг нумерации печей внутри серии.
 * Вторая цифра K — шаг перехода к следующей серии: серии обходятся
 * циклически по модулю S. Например, для 9-2 порядок серий:
 * {@code 1 → 3 → 5 → 7 → 9 → 2 → 4 → 6 → 8}.
 *
 * <p>Печь серии {@code i} — это {@code i, i+S, i+2S, … ≤ N}. Внутри серии
 * выдача идёт по возрастанию номера, серии — в порядке, заданном K.
 *
 * <p>Для полного обхода всех серий необходимо {@code gcd(S, K) = 1}.
 */
public final class Seriality {

    /** Разобранная схема "9-2" → series=9, seriesStep=2. */
    public record Scheme(int series, int seriesStep) {
        public Scheme {
            if (series < 2)
                throw new IllegalArgumentException("Число серий S должно быть ≥ 2, получено: " + series);
            if (seriesStep < 1)
                throw new IllegalArgumentException("Шаг серий K должен быть ≥ 1, получено: " + seriesStep);
            if (seriesStep >= series)
                throw new IllegalArgumentException(
                        "Нерабочая схема S-K: шаг серий K=%d должен быть меньше числа серий S=%d"
                                .formatted(seriesStep, series));
            if (gcd(series, seriesStep) != 1)
                throw new IllegalArgumentException(
                        "Нерабочая схема S-K: gcd(S=%d, K=%d) ≠ 1, обход не покроет все серии"
                                .formatted(series, seriesStep));
        }

        public static Scheme parse(String code) {
            var norm = code.replace("–", "-").replace("—", "-").strip();
            var parts = norm.split("-");
            if (parts.length != 2)
                throw new IllegalArgumentException("Формат серийности S-K (пример: 9-2), получено: " + code);
            return new Scheme(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        @Override
        public String toString() { return series + "-" + seriesStep; }
    }

    /**
     * Порядок выдачи за один оборот: серии обходятся с шагом K по модулю S,
     * внутри серии — через S печей.
     *
     * @param ovenCount число печей в батарее
     * @param scheme    разобранная схема
     * @return номера печей в порядке выдачи, суммарно ровно ovenCount номеров
     */
    public static int[] dischargeOrder(int ovenCount, Scheme scheme) {
        if (ovenCount < 2)
            throw new IllegalArgumentException("В батарее должно быть ≥ 2 печей");
        List<Integer> order = new ArrayList<>(ovenCount);
        int series = 1;
        for (int block = 0; block < scheme.series(); block++) {
            for (int n = series; n <= ovenCount; n += scheme.series())
                order.add(n);
            series = nextSeries(series, scheme);
        }
        if (order.size() != ovenCount)
            throw new IllegalStateException("Порядок выдачи не покрывает все печи: %d из %d"
                    .formatted(order.size(), ovenCount));
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Следующая серия при шаге K: ((i − 1 + K) mod S) + 1. */
    public static int nextSeries(int series, Scheme scheme) {
        return ((series - 1 + scheme.seriesStep()) % scheme.series()) + 1;
    }

    /** Номер серии печи: печь n принадлежит серии ((n-1) mod S) + 1. */
    public static int seriesOf(int ovenNo, Scheme scheme) {
        return ((ovenNo - 1) % scheme.series()) + 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private Seriality() { }
}
