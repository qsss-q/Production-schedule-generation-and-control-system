package ru.evraz.koks.sim;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Математика серийности по docs/05 §2.3 — таблица раскладок для 69 печей. */
class SerialityTest {

    @Test
    void scheme92_orderFor69Ovens_matchesDocumentation() {
        var scheme = Seriality.Scheme.parse("9-2");
        var order = Seriality.dischargeOrder(69, scheme);
        assertEquals(69, order.length);

        // Серии обходятся с шагом K=2: 1 → 3 → 5 → 7 → 9 → 2 → 4 → 6 → 8.
        // Внутри серии — шаг S=9.
        int[] blockStarts = {1, 3, 5, 7, 9, 2, 4, 6, 8};
        int[] blockLengths = {8, 8, 8, 7, 7, 8, 8, 8, 7};
        assertBlocks(order, scheme, blockStarts, blockLengths);

        assertEquals(1, order[0]);
        assertEquals(10, order[1]);
        assertEquals(64, order[7]);
        assertEquals(3, order[8]);   // начало серии 3, а не 2
        assertEquals(66, order[15]);
        assertEquals(5, order[16]);  // начало серии 5
        assertEquals(62, order[68]); // последняя печь серии 8

        // Каждая печь встречается ровно один раз.
        var seen = new HashSet<Integer>();
        for (int p : order) assertTrue(seen.add(p), "печь " + p + " повторилась");

        // seriesOf: печь n в серии ((n−1) mod S)+1.
        assertEquals(1, Seriality.seriesOf(64, scheme)); // 64 = 1+9k → серия 1
        assertEquals(1, Seriality.seriesOf(10, scheme));
        assertEquals(9, Seriality.seriesOf(9, scheme));
        assertEquals(3, Seriality.seriesOf(30, scheme)); // (29 mod 9)+1 = 3
    }

    @Test
    void scheme92_seriesCycleWrapsByModulo() {
        var scheme = Seriality.Scheme.parse("9-2");
        int series = 1;
        int[] expected = {1, 3, 5, 7, 9, 2, 4, 6, 8, 1};
        for (int value : expected) {
            assertEquals(value, series);
            series = Seriality.nextSeries(series, scheme);
        }
    }

    @Test
    void scheme92_differsFromScheme91() {
        var order92 = Seriality.dischargeOrder(69, Seriality.Scheme.parse("9-2"));
        var order91 = Seriality.dischargeOrder(69, Seriality.Scheme.parse("9-1"));
        assertNotEquals(order91[8], order92[8], "9-2 не должен совпадать с 9-1");
        assertEquals(2, order91[8]);
        assertEquals(3, order92[8]);
    }

    @Test
    void scheme52_orderFor69Ovens_usesSeriesStep() {
        var scheme = Seriality.Scheme.parse("5-2");
        var order = Seriality.dischargeOrder(69, scheme);
        assertEquals(69, order.length);

        // Серии: 1 → 3 → 5 → 2 → 4, внутри серии шаг 5.
        int[] blockStarts = {1, 3, 5, 2, 4};
        int[] blockLengths = {14, 14, 13, 14, 14};
        assertBlocks(order, scheme, blockStarts, blockLengths);

        assertEquals(1, order[0]);
        assertEquals(6, order[1]);   // 1,6,11,… шаг 5
        assertEquals(11, order[2]);
        assertEquals(3, order[14]);  // начало серии 3
        assertEquals(5, order[28]);  // начало серии 5
        assertEquals(2, order[41]);  // начало серии 2
        assertEquals(4, order[55]);  // начало серии 4
    }

    @Test
    void scheme21_evenOddSplit() {
        var scheme = Seriality.Scheme.parse("2-1");
        var order = Seriality.dischargeOrder(69, scheme);
        // Серия 1 — нечётные печи (35 штук), серия 2 — чётные (34).
        assertEquals(1, Seriality.seriesOf(1, scheme)); // (1-1) mod 2 + 1 = 1
        assertEquals(2, Seriality.seriesOf(2, scheme)); // (2-1) mod 2 + 1 = 2
        assertEquals(1, Seriality.seriesOf(69, scheme));
        assertEquals(69, order.length);
        assertEquals(35, countOfSeries(order, scheme, 1));
        assertEquals(34, countOfSeries(order, scheme, 2));
        // После всех нечётных начинается чётная серия.
        assertEquals(2, order[35]);
        assertEquals(2, Seriality.seriesOf(2, scheme));
    }

    private static void assertBlocks(int[] order, Seriality.Scheme scheme,
                                     int[] blockStarts, int[] blockLengths) {
        int offset = 0;
        for (int block = 0; block < blockStarts.length; block++) {
            int series = blockStarts[block];
            assertEquals(blockLengths[block], countOfSeries(order, scheme, series),
                    "неверное число печей в серии " + series);
            for (int i = 0; i < blockLengths[block]; i++) {
                int expected = series + i * scheme.series();
                assertEquals(expected, order[offset + i],
                        "серия %d, позиция %d".formatted(series, i));
            }
            offset += blockLengths[block];
        }
        assertEquals(order.length, offset);
    }

    private static int countOfSeries(int[] order, Seriality.Scheme scheme, int series) {
        int n = 0;
        for (int p : order) if (Seriality.seriesOf(p, scheme) == series) n++;
        return n;
    }

    @Test
    void parsing_acceptsDashVariants_andRejectsBadInput() {
        assertEquals(new Seriality.Scheme(9, 2), Seriality.Scheme.parse("9-2"));
        assertEquals(new Seriality.Scheme(9, 2), Seriality.Scheme.parse("9–2"));
        assertEquals(new Seriality.Scheme(2, 1), Seriality.Scheme.parse(" 2-1 "));
        assertEquals(2, Seriality.Scheme.parse("9-2").seriesStep());
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9"));
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9.2"));
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("2-5")); // K > S
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9-3")); // gcd(9,3) ≠ 1
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9-9")); // K ≥ S
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9-0")); // K < 1
    }
}
