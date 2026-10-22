// controller/TemperatureController.java
package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.OvenTemperatureReading;
import org.example.coke_factory.repository.OvenTemperatureReadingRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/temperature")
@RequiredArgsConstructor
public class TemperatureController {

    private final OvenTemperatureReadingRepository repository;

    // график температуры печи: /api/temperature/oven/1  или  ?from=...&to=...
    @GetMapping("/oven/{ovenId}")
    public List<OvenTemperatureReading> getByOven(
            @PathVariable Long ovenId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        if (from != null && to != null) {
            return repository.findByOvenIdAndRecordedAtBetweenOrderByRecordedAtAsc(ovenId, from, to);
        }
        return repository.findByOvenIdOrderByRecordedAtAsc(ovenId);
    }

    // сюда симулятор (или Иван) будет записывать новые замеры
    @PostMapping
    public OvenTemperatureReading create(@RequestBody OvenTemperatureReading reading) {
        reading.setId(null);
        return repository.save(reading);
    }
}
