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
        // Серия 1: 1,10,19,…,64 (8 выдач), далее серия 2 и т.д.
        assertEquals(1, order[0]);
        assertEquals(10, order[1]);
        assertEquals(64, order[7]);
        assertEquals(2, order[8]); // начало серии 2
        // Каждая печь встречается ровно один раз
        var seen = new HashSet<Integer>();
        for (int p : order) assertTrue(seen.add(p), "печь " + p + " повторилась");
        // seriesOf: печь n в серии ((n−1) mod S)+1
        assertEquals(1, Seriality.seriesOf(64, scheme)); // 64 = 1+9k → серия 1
        assertEquals(1, Seriality.seriesOf(10, scheme));
        assertEquals(9, Seriality.seriesOf(9, scheme));
        assertEquals(3, Seriality.seriesOf(30, scheme)); // (29 mod 9)+1 = 3
    }

    @Test
    void scheme52_sevenSeriesSumIs69() {
        var scheme = Seriality.Scheme.parse("5-2");
        var order = Seriality.dischargeOrder(69, scheme);
        assertEquals(69, order.length);
        assertEquals(1, order[0]);
        assertEquals(6, order[1]);   // 1,6,11,… шаг 5
        assertEquals(11, order[2]);
    }

    @Test
    void scheme21_evenOddSplit() {
        var scheme = Seriality.Scheme.parse("2-1");
        var order = Seriality.dischargeOrder(69, scheme);
        // Серия 1 — нечётные печи (35 штук), серия 2 — чётные (34)
        assertEquals(1, Seriality.seriesOf(1, scheme)); // (1-1) mod 2 + 1 = 1
        assertEquals(2, Seriality.seriesOf(2, scheme)); // (2-1) mod 2 + 1 = 2
        assertEquals(1, Seriality.seriesOf(69, scheme));
        assertEquals(69, order.length);
        assertEquals(35, countOfSeries(order, scheme, 1));
        assertEquals(34, countOfSeries(order, scheme, 2));
        // После всех нечётных начинается чётная серия
        assertEquals(2, order[35]);
        assertEquals(2, Seriality.seriesOf(2, scheme));
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
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9"));
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("9.2"));
        assertThrows(IllegalArgumentException.class, () -> Seriality.Scheme.parse("2-5")); // K > S
    }
}
