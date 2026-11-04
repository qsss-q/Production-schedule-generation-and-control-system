// controller/NotificationController.java (замена)
package org.example.coke_factory.controller;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.Notification;
import org.example.coke_factory.entity.NotificationType;
import org.example.coke_factory.repository.NotificationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository repository;

    // Журнал событий с фильтрами. Все параметры необязательные:
    // /api/notifications?equipmentId=3&type=ACCIDENT&from=2026-10-05T08:00:00&to=2026-10-05T16:00:00
    @GetMapping
    public List<Notification> search(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        Specification<Notification> spec = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (equipmentId != null) {
                conditions.add(cb.equal(root.get("equipment").get("id"), equipmentId));
            }
            if (type != null) {
                conditions.add(cb.equal(root.get("type"), type));
            }
            if (from != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), from));
            }
            if (to != null) {
                conditions.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("createdAt"), to));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };

        return repository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    // блок "Отклонения" (непрочитанные)
    @GetMapping("/unread")
    public List<Notification> getUnread() {
        return repository.findByIsReadFalseOrderByCreatedAtDesc();
    }

    @PostMapping
    public Notification create(@RequestBody Notification notification) {
        notification.setId(null);
        return repository.save(notification);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Notification> markRead(@PathVariable Long id) {
        return repository.findById(id)
                .map(n -> {
                    n.setIsRead(true);
                    return ResponseEntity.ok(repository.save(n));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}