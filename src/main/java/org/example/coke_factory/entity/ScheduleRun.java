package org.example.coke_factory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Прогон планировщика (docs/10 §6.1): какая версия графика, каким алгоритмом
 * и на каком входе посчитана. Каждая пересчитанная версия — новый прогон
 * с parentId; старый не изменяется, поэтому история графика сохраняется.
 */
@Entity
@Table(name = "schedule_runs")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleRun {

    @Id
    @Column(length = 36, nullable = false)
    private String id = java.util.UUID.randomUUID().toString();

    /** От какого прогона получен (пересчёт по возмущению, ручная версия). */
    @Column(name = "parent_id", length = 36)
    private String parentId;

    /** b1_fifo | b2_idle | b3_smooth. */
    @Column(name = "algorithm", nullable = false, length = 20)
    private String algorithm;

    /** Схема серийности входа: 2-1 | 5-2 | 9-2. */
    @Column(name = "seriality", nullable = false, length = 10)
    private String seriality;

    @Column(nullable = false)
    private Long seed;

    /** Причина создания: «формирование», «возмущение: отказ…», «ручная правка». */
    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status = RunStatus.DRAFT;

    /** Отпечаток входа: момент, ревизия, алгоритм, схема, seed, горизонт. */
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    /** Метрики прогона M1–M9 + skipped + excluded, JSON. */
    @Column(name = "metrics_json", columnDefinition = "text")
    private String metricsJson;

    /** Модельное окно офлайн-реплея: от и до, мин симуляции. */
    @Column(name = "window_from_min", nullable = false)
    private long windowFromMin;

    @Column(name = "window_to_min", nullable = false)
    private long windowToMin;

    /** Политика удержания (docs/10 §5.7): пока сохраняется, логика — следующая задача. */
    @Column(name = "hold_notify_min", nullable = false)
    private Integer holdNotifyMin = 5;

    @Column(name = "hold_auto_skip_min", nullable = false)
    private Integer holdAutoSkipMin = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
