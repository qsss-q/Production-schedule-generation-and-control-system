// repository/ScheduleSettingsRepository.java
package org.example.coke_factory.repository;

import org.example.coke_factory.entity.ScheduleSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScheduleSettingsRepository extends JpaRepository<ScheduleSettings, Long> {
    Optional<ScheduleSettings> findFirstByActiveTrue();
}