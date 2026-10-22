Бэкенд поднят, можно заменять захардкоженные данные на запросы к API.



КАК ЗАПУСТИТЬ

В папке с pom.xml: docker compose up --build

API: http://localhost:8080

Документация (все адреса, формат запросов и ответов, можно нажимать и пробовать): http://localhost:8080/swagger-ui.html

CORS для localhost:5173 настроен.

При первом запуске БД заполняется тестовыми данными: 60 печей, выталкиватель, тушильный вагон, операции, отклонения и журнал, как у тебя на экране.



ЧТО ОТКУДА БРАТЬ

\- Список печей и карточки (состояние, % готовности, температура): GET /api/ovens

\- Справочник оборудования (печи, выталкиватель, тушильный вагон, доступ): GET /api/equipment

\- Кнопка «+ Новый»: POST /api/equipment

\- Переключатель доступности П-1...: PATCH /api/equipment/{id}/availability?available=false

\- Блок «Отклонения»: GET /api/notifications/unread

\- Журнал событий с фильтрами: GET /api/notifications?equipmentId=\&type=\&from=\&to=

\- Лента ближайших событий: GET /api/operations/upcoming?from=2026-10-05T11:10:00

\- Операции графика / временная шкала: GET /api/operations и GET /api/operations/range?from=...\&to=...

\- Сменить статус операции: PATCH /api/operations/{id}/status?status=DONE

\- Настройки слева (шаг, окно смены, макс. простой, приоритет, алгоритм): GET /api/settings/active, сохранение: PUT /api/settings/{id}

\- Действия операции: GET /api/audit, добавить запись: POST /api/audit



ФОРМАТ ДАННЫХ

\- Всё в JSON, даты строкой вида 2026-10-05T11:10:00

\- Имена полей как в Swagger, в camelCase: nameOven, progressPercent, plannedStart, deviationMinutes, isRead и т. д.

\- Состояния и типы приходят английскими константами, русские подписи подставляешь у себя словарём:

&#x20; состояние печи: COKING, READY, PUSHING, REPAIR

&#x20; тип оборудования: OVEN, PUSHER, QUENCHING\_CAR

&#x20; статус операции: PLANNED, IN\_PROGRESS, DONE, OVERDUE, IMPOSSIBLE

&#x20; тип уведомления: ACCIDENT, SCHEDULE\_BREAK, DEVIATION, STOP, INFO

\- Операция содержит вложенный объект печи: operation.oven.nameOven



Ещё не сделано: график температуры, симулятор, сравнение алгоритмов, формирование графика (эта логика у Ивана). Пока оставляй их захардкоженными.

