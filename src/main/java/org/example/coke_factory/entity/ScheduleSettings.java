package org.example.coke_factory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

@Entity
@Table(name = "schedule_settings")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // допустимый шаг между выдачами, минуты
    @Column(name = "step_minutes")
    private Integer stepMinutes;

    // окно смены: 08:00 - 20:00
    @Column(name = "shift_start")
    private LocalTime shiftStart;

    @Column(name = "shift_end")
    private LocalTime shiftEnd;

    // порог опоздания до статуса OVERDUE, минуты (делится на приоритет операции)
    @Column(name = "max_idle_minutes")
    private Integer maxIdleMinutes;

    // устарело: срочность живёт в плане выпуска (H9), а не в порядке печей (docs/05 §5)
    @Column(name = "urgent_priority")
    private String urgentPriority;

    // алгоритм: "FIFO" (B1), "IDLE_OPTIMIZATION" (B2), "SMOOTH" (B3);
    // значение "URGENT_FIRST" выведено из употребления — см. docs/10 §6.2
    private String algorithm;

    // приоритет по умолчанию для новых операций: 1 — обычный, 2 — высокий, 3 — срочный
    @Column(name = "default_priority")
    private Integer defaultPriority = 1;

    // какие настройки сейчас применены
    private Boolean active = false;
}