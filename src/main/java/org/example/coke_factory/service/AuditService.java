package org.example.coke_factory.service;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.AuditLog;
import org.example.coke_factory.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

/**
 * Запись в журнал действий оператора (таблица audit_log).
 * Время записи — текущее время симуляции по часам предприятия,
 * чтобы журнал действий совпадал по времени с журналом событий на экране.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    // пока без авторизации: все действия выполняет «оператор смены»
    private static final String DEFAULT_USER = "operator";

    private final AuditLogRepository repository;
    private final SimulatorService simulator;

    public AuditLog log(String action, String entityName, Long entityId, String details) {
        AuditLog entry = new AuditLog();
        entry.setUsername(DEFAULT_USER);
        entry.setAction(action);
        entry.setEntityName(entityName);
        entry.setEntityId(entityId);
        entry.setDetails(details);
        entry.setCreatedAt(simulator.simLocalTime());
        return repository.save(entry);
    }
}
