// entity/OvenTemperatureReading.java
package org.example.coke_factory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "oven_temperature_readings")
@Getter
@Setter
@NoArgsConstructor
public class OvenTemperatureReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "oven_id", nullable = false)
    private CokeOvens oven;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    // температура в камере, °C
    @Column(nullable = false)
    private Double temperature;
}
