-- ОБОРУДОВАНИЕ: 60 печей + выталкиватель (недоступен) + тушильный вагон
INSERT INTO equipment (name, type, available)
SELECT * FROM (
                  SELECT 'Печь П-' || g AS name, 'OVEN' AS type, true AS available
                  FROM generate_series(1, 60) AS g
                  UNION ALL SELECT 'Выталкиватель', 'PUSHER', false
                  UNION ALL SELECT 'Тушильный вагон №1', 'QUENCHING_CAR', true
              ) AS t
WHERE NOT EXISTS (SELECT 1 FROM equipment);

-- ПЕЧИ: у П-1 состояние "готова к выгрузке, 100 %", как на экране
INSERT INTO coke_ovens (name_oven, battery_number, state, progress_percent, coal_mark,
                        cur_efficiency_oven, cur_temp, equipment_id)
SELECT e.name, 1,
       CASE WHEN e.n % 10 = 0 THEN 'REPAIR'
            WHEN e.n % 4 = 1 THEN 'READY'
            WHEN e.n % 4 = 2 THEN 'PUSHING'
            ELSE 'COKING' END,
       CASE WHEN e.n % 10 = 0 THEN 0
            WHEN e.n % 4 = 1 THEN 100
            ELSE (e.n * 7) % 90 END,
       'Ж',
       90 + (e.n % 8),
       1000 + (e.n % 5) * 10,
       e.id
FROM (SELECT id, name, row_number() OVER (ORDER BY id) AS n
      FROM equipment WHERE type = 'OVEN') AS e
WHERE NOT EXISTS (SELECT 1 FROM coke_ovens);

-- НАСТРОЙКИ РАСПИСАНИЯ
INSERT INTO schedule_settings (name, step_minutes, shift_start, shift_end,
                               max_idle_minutes, urgent_priority, algorithm, active)
SELECT 'Вариант 2 - оптимизация простоев', 15, TIME '08:00', TIME '20:00',
       30, 'HIGH', 'IDLE_OPTIMIZATION', true
    WHERE NOT EXISTS (SELECT 1 FROM schedule_settings);

-- ОПЕРАЦИИ ГРАФИКА (на сегодняшнюю дату)
INSERT INTO schedule_operations (oven_id, operation_type, planned_start, planned_end, status,
                                 priority, manual, deviation_minutes, order_number, urgent)
SELECT o.id, v.op_type, CURRENT_DATE + v.start_time, CURRENT_DATE + v.end_time,
       v.status, v.priority, v.manual, v.deviation, v.order_no, v.urgent
FROM (VALUES
          ('Печь П-1', 'PUSHING', TIME '08:00', TIME '08:30', 'DONE',        1, false,  0, '№12', false),
          ('Печь П-4', 'PUSHING', TIME '11:00', TIME '11:37', 'IN_PROGRESS', 1, false,  0, '№15', false),
          ('Печь П-3', 'PUSHING', TIME '12:30', TIME '13:00', 'OVERDUE',     2, true,  25, '№17', true),
          ('Печь П-5', 'PUSHING', TIME '14:00', TIME '14:30', 'IMPOSSIBLE',  1, false,  0, '№19', false),
          ('Печь П-2', 'PUSHING', TIME '17:03', TIME '17:33', 'PLANNED',     2, false,  6, '№21', true)
     ) AS v(oven_name, op_type, start_time, end_time, status, priority, manual, deviation, order_no, urgent)
         JOIN coke_ovens o ON o.name_oven = v.oven_name
WHERE NOT EXISTS (SELECT 1 FROM schedule_operations);

-- УВЕДОМЛЕНИЯ И ЖУРНАЛ СОБЫТИЙ
INSERT INTO notifications (equipment_id, type, title, message, created_at, is_read)
SELECT e.id, v.type, v.title, v.message, CURRENT_DATE + v.t, v.is_read
FROM (VALUES
          ('Выталкиватель',      'ACCIDENT',       'Авария · Выталкиватель',          'Блокировка привода: выдача П-5 невозможна', TIME '10:20', false),
          ('Печь П-3',           'SCHEDULE_BREAK', 'Срыв графика · П-3',              'Передержка кокса +25 мин',                  TIME '10:58', false),
          ('Печь П-2',           'DEVIATION',      'Отступление · П-2',               'Прогноз +6 мин к плану',                    TIME '10:30', false),
          ('Тушильный вагон №1', 'INFO',           'Информация · Тушильный вагон №1', 'Переход на резервный путь №2',              TIME '10:50', false),
          ('Печь П-1',           'INFO',           'Печь П-1',                        'Коксование завершено',                      TIME '11:05', true),
          ('Печь П-4',           'INFO',           'Печь П-4',                        'Начало выгрузки',                           TIME '10:52', true),
          ('Печь П-5',           'INFO',           'Печь П-5',                        'Загрузка шихты завершена',                  TIME '09:30', true)
     ) AS v(equipment_name, type, title, message, t, is_read)
         JOIN equipment e ON e.name = v.equipment_name
WHERE NOT EXISTS (SELECT 1 FROM notifications);

-- ДЕЙСТВИЯ ОПЕРАТОРА
INSERT INTO audit_log (username, action, entity_name, details, created_at)
SELECT 'operator', 'MOVE_OPERATION', 'ScheduleOperation',
       'Выгрузка П-3 → 12:30 (вручную)', CURRENT_DATE + TIME '11:05'
    WHERE NOT EXISTS (SELECT 1 FROM audit_log);