// repository/OvenTemperatureReadingRepository.java
package org.example.coke_factory.repository;

import org.example.coke_factory.entity.OvenTemperatureReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OvenTemperatureReadingRepository extends JpaRepository<OvenTemperatureReading, Long> {

    List<OvenTemperatureReading> findByOvenIdOrderByRecordedAtAsc(Long ovenId);

    List<OvenTemperatureReading> findByOvenIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long ovenId, LocalDateTime from, LocalDateTime to);

    // замеры по названию печи («Печь П-12»): так фронт обращается по номеру печи
    @Query("select r from OvenTemperatureReading r where r.oven.nameOven = :name order by r.recordedAt asc")
    List<OvenTemperatureReading> findByOvenName(@Param("name") String name);
}
