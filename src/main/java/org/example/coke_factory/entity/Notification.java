// entity/Notification.java (замена)
package org.example.coke_factory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // к какому оборудованию относится (может быть пустым)
    @ManyToOne
    @JoinColumn(name = "equipment_id")
    private Equipment equipment;

    // к какой операции относится (может быть пустым)
    @ManyToOne
    @JoinColumn(name = "operation_id")
    private ScheduleOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    // "Авария · Выталкиватель"
    @Column(nullable = false)
    private String title;

    // "Блокировка привода: выдача П-5 невозможна"
    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "is_read")
    private Boolean isRead = false;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}