// repository/ScheduleOperationRepository.java
package org.example.coke_factory.repository;

import org.example.coke_factory.entity.OperationStatus;
import org.example.coke_factory.entity.ScheduleOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduleOperationRepository extends JpaRepository<ScheduleOperation, Long> {

    List<ScheduleOperation> findByOvenId(Long ovenId);

    List<ScheduleOperation> findByStatus(OperationStatus status);

    // операции, которые пересекаются с окном времени [from, to] (для временной шкалы)
    List<ScheduleOperation> findByPlannedStartLessThanEqualAndPlannedEndGreaterThanEqual(
            LocalDateTime to, LocalDateTime from);
    // ближайшие операции для "ленты событий"
    List<ScheduleOperation> findTop10ByPlannedStartAfterOrderByPlannedStartAsc(LocalDateTime from);
}