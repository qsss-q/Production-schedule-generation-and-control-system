# Бэкенд: система формирования производственного графика (кокс)

Spring Boot 3.4 + PostgreSQL + Docker, сборка Gradle. Симулятор батареи (код Вани) лежит в папке `simulator/`
и подключён как отдельная сборка.

## Запуск

Нужен Docker (и JDK 21, если запускать из IDE).

```
docker compose up --build
```

- API: http://localhost:8080
- Swagger (все адреса, формат запросов и ответов): http://localhost:8080/swagger-ui.html
- Остановить: `Ctrl+C`, затем `docker compose down`. Сбросить БД: `docker compose down -v`.

При каждом запуске приложение пересоздаёт таблицы (`ddl-auto: create`) и заполняет их тестовыми данными из
`src/main/resources/data.sql`: 69 печей, выталкиватель (недоступен), тушильный вагон, настройки расписания,
температура по печам.

### Запуск из IntelliJ IDEA

1. File → Open → выбрать файл `build.gradle.kts` → Open as Project. Gradle JVM: JDK 21.
2. База в Docker: `docker compose up coke-postgres-db`
3. Запустить `CokeFactoryApplication`.

### Сборка и тесты без Docker

```
./gradlew build          # Windows: gradlew.bat build
./gradlew test           # тесты симулятора и SimulatorService (БД не нужна)
```

## Откуда фронт берёт данные

Два набора адресов.

**Живая симуляция** (`/api/v1/...`, ответ в конверте `{ success, data, error }`, контракт Вани, docs/08):

| Что на экране | Запрос |
|---|---|
| Время симуляции, скорость, счётчики | `GET /api/v1/state` |
| 69 печей: состояние, % готовности, отклонение | `GET /api/v1/ovens?zoom=battery` |
| Выталкиватель и тушильный вагон вдоль батареи | `GET /api/v1/machines` |
| Лента ближайших выдач | `GET /api/v1/feed?limit=20` |
| Журнал событий и отклонения | `GET /api/v1/events?limit=200` |
| ПУСК / ПАУЗА / СТОП | `POST /api/v1/simulator/control` `{action: start\|pause\|step\|reset}` |
| Окно «Симулятор» (ползунки, seed) | `PUT /api/v1/simulator/config` |
| «Сгенерировать отказ» | `POST /api/v1/simulator/failure` `{type: pushJam\|ovenHeating}` |
| «Сформировать график» (сохраняет операции в БД) | `POST /api/v1/schedule/generate` |
| Запись действия оператора в журнал | `POST /api/v1/operator-actions` `{summary}` |

**Данные из БД** (`/api/...`, обычный JSON):

| Что на экране | Запрос |
|---|---|
| Справочник оборудования, доступность | `GET /api/equipment`, `PATCH /api/equipment/{id}/availability?available=` |
| Настройки слева (шаг, окно смены, макс. простой, приоритет) | `GET /api/settings/active`, `PUT /api/settings/{id}` |
| Действия оператора | `GET /api/audit` |
| График температуры печи | `GET /api/temperature/by-oven-number/{номер печи}` |
| Плановые операции графика | `GET /api/operations`, `GET /api/operations/upcoming?from=` |

Остальные адреса (печи в БД, уведомления, операции) см. в Swagger.

## Структура

```
src/main/java/org/example/coke_factory/
  entity/       таблицы БД (Equipment, CokeOvens, ScheduleOperation, ScheduleSettings, Notification, AuditLog, ...)
  repository/   доступ к БД (Spring Data JPA)
  controller/   REST: /api/... (БД) и SimulatorController (/api/v1, симуляция)
  service/      SimulatorService (обёртка над симулятором), ScheduleService (график -> БД), AuditService (журнал)
  dto/          ApiResponse (конверт ответа симулятора)
  exception/    обработчик ошибок для /api/v1
  config/       CorsConfig
simulator/      симулятор батареи (код Вани, отдельная Gradle-сборка)
```

## Как это связано

- `SimulatorService` держит один `Simulator` в памяти и раз в секунду двигает его. Скорость x60 = 1 секунда реального
  времени равна 1 минуте симуляции. После запуска приложения симуляция на паузе, ПУСК запускает её.
- Действия оператора (пуск, пауза, сброс, отказ, настройки, формирование графика) пишутся в таблицу `audit_log`
  со временем симуляции.
- «Сформировать график» берёт прогноз выдач у симулятора и сохраняет в `schedule_operations`. Реальный алгоритм
  планирования (B1-B4) пока заглушка, это зона Вани.
- Температура и справочник берутся из БД (демо-данные): у симулятора телеметрии пока нет.
