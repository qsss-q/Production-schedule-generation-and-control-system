package org.example.coke_factory.entity;

/** Жизненный цикл прогона планировщика (docs/10 §6.1). */
public enum RunStatus {
    DRAFT,       // создан, но не посчитан
    CALCULATED,  // посчитан, план установлен в симулятор
    ARCHIVED     // заменён пересчётом или новым прогоном; история сохраняется
}
