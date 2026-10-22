// repository/OvenTemperatureReadingRepository.java
package org.example.coke_factory.repository;

import org.example.coke_factory.entity.OvenTemperatureReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OvenTemperatureReadingRepository extends JpaRepository<OvenTemperatureReading, Long> {

    List<OvenTemperatureReading> findByOvenIdOrderByRecordedAtAsc(Long ovenId);

    List<OvenTemperatureReading> findByOvenIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long ovenId, LocalDateTime from, LocalDateTime to);
}