package org.example.coke_factory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coke_ovens")
@Getter
@Setter
@NoArgsConstructor
public class CokeOvens {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_oven", nullable = false)
    private String nameOven;

    // номер коксовой батареи
    @Column(name = "battery_number")
    private Integer batteryNumber;

    // состояние печи (карточка: "готова к выгрузке")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OvenState state = OvenState.COKING;

    // ход процесса, 0..100 (карточка: "100 %")
    @Column(name = "progress_percent")
    private Double progressPercent = 0.0;

    @Column(name = "coal_mark")
    private String coalMark;

    @Column(name = "cur_efficiency_oven")
    private Double curEfficiencyOven;

    @Column(name = "cur_temp")
    private Double curTemp;

    // связь со справочником оборудования
    @ManyToOne
    @JoinColumn(name = "equipment_id")
    private Equipment equipment;
}