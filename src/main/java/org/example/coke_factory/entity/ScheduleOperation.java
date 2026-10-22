package org.example.coke_factory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "schedule_operations")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "oven_id", nullable = false)
    private CokeOvens oven;

    // например: "PUSHING" (выдача кокса), "CHARGING" (загрузка)
    @Column(name = "operation_type", nullable = false)
    private String operationType;

    @Column(name = "planned_start", nullable = false)
    private LocalDateTime plannedStart;

    @Column(name = "planned_end", nullable = false)
    private LocalDateTime plannedEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationStatus status = OperationStatus.PLANNED;

    @Column(nullable = false)
    private Integer priority = 1;

    // назначена вручную оператором
    @Column(nullable = false)
    private Boolean manual = false;

    // отклонение от плана, минуты ("+25 мин"), может быть отрицательным
    @Column(name = "deviation_minutes")
    private Integer deviationMinutes = 0;

    // номер заказа ("№17")
    @Column(name = "order_number")
    private String orderNumber;

    // срочный заказ
    @Column(nullable = false)
    private Boolean urgent = false;

    private String note;
}