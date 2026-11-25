package org.example.coke_factory.repository;

import org.example.coke_factory.entity.RunStatus;
import org.example.coke_factory.entity.ScheduleRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, String> {

    List<ScheduleRun> findAllByOrderByCreatedAtDesc();

    List<ScheduleRun> findByStatus(RunStatus status);

    /** Действующий прогон — последний посчитанный. */
    Optional<ScheduleRun> findTopByStatusOrderByCreatedAtDesc(RunStatus status);
}
