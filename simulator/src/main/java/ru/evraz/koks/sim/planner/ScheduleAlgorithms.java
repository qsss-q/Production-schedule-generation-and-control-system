package ru.evraz.koks.sim.planner;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Реестр алгоритмов планирования: поиск по коду из запроса и полный список для сравнения. */
public final class ScheduleAlgorithms {

    private static final List<ScheduleAlgorithm> ALL =
            List.of(new B1Fifo(), new B2Idle(), new B3Smooth());

    private static final Map<String, ScheduleAlgorithm> BY_CODE = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(ScheduleAlgorithm::code, Function.identity()));

    /** Алгоритм по коду; неизвестный код — IllegalArgumentException с перечнем. */
    public static ScheduleAlgorithm byCode(String code) {
        ScheduleAlgorithm algo = code == null ? null : BY_CODE.get(code.strip());
        if (algo == null)
            throw new IllegalArgumentException(
                    "неизвестный алгоритм %s, доступно: %s".formatted(
                            code == null ? "null" : code.strip(),
                            BY_CODE.keySet().stream().sorted().collect(Collectors.joining(", "))));
        return algo;
    }

    /** Все алгоритмы — для сравнения на одном входе. */
    public static List<ScheduleAlgorithm> all() {
        return ALL;
    }

    private ScheduleAlgorithms() { }
}
