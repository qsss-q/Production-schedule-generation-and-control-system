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

    // максимальный простой до срыва, минуты
    @Column(name = "max_idle_minutes")
    private Integer maxIdleMinutes;

    // приоритет срочных заказов: "LOW", "MEDIUM", "HIGH"
    @Column(name = "urgent_priority")
    private String urgentPriority;

    // алгоритм: "FIFO" (В1), "IDLE_OPTIMIZATION" (В2), "URGENT_FIRST" (В3)
    private String algorithm;

    // какие настройки сейчас применены
    private Boolean active = false;
}