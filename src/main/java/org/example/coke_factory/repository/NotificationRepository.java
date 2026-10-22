// repository/NotificationRepository.java (замена)
package org.example.coke_factory.repository;

import org.example.coke_factory.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository
        extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {

    List<Notification> findByIsReadFalseOrderByCreatedAtDesc();
}