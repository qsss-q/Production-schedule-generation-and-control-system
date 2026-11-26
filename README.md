# Бэкенд: система формирования производственного графика (кокс)

Spring Boot 3.4 + PostgreSQL + Docker, сборка Gradle. Симулятор батареи (код Вани) лежит в папке `simulator/`
и подключён как отдельная сборка.

## Запуск

Нужен Docker (и JDK 21, если запускать из IDE).

Первый запуск PowerShell:
```powershell
Copy-Item .env.example .env
# Откройте .env и замените значение POSTGRES_PASSWORD на локальный пароль
docker compose up --build
```

Linux/macOS:
```bash
cp .env.example .env
# задайте локальный POSTGRES_PASSWORD в .env
docker compose up --build
```

docker compose logs tunnel - получить ссылку

можно открыть и по локалхосту:
- Интерфейс: http://localhost:5173
- API: http://localhost:8080
- Swagger (все адреса, формат запросов и ответов): http://localhost:8080/swagger-ui.html
- Остановить: `Ctrl+C`, затем `docker compose down`. Сбросить БД: `docker compose down -v`.
- Пароль БД берётся из `.env`; не отправляйте реальный `.env` в git и не используйте демонстрационный пароль в production.

При запуске Hibernate обновляет схему без удаления существующих данных (`ddl-auto: update`).
Начальные демонстрационные данные из `src/main/resources/data.sql` добавляются только если
соответствующие таблицы пусты: 69 печей, четыре машины, настройки расписания, примерные операции,
уведомления и температурные измерения. Температуры — демонстрационные, не live-телеметрия.
Для промышленного развёртывания рекомендуется перейти на Flyway-миграции и `ddl-auto: validate`.

### Запуск из IntelliJ IDEA

1. File → Open → выбрать файл `build.gradle.kts` → Open as Project. Gradle JVM: JDK 21.
2. Создать `.env` по инструкции выше и запустить БД: `docker compose up coke-postgres-db`.
3. Перед запуском из IDE установить `SPRING_DATASOURCE_PASSWORD` в то же значение, что `POSTGRES_PASSWORD` в `.env`.
   В PowerShell: `$env:SPRING_DATASOURCE_PASSWORD = "ваш_пароль"`.
4. Запустить `CokeFactoryApplication`.

в dockeryaml поставить тот же пароль
### Сборка и тесты

```bash
./gradlew test                 # тесты backend; Windows: gradlew.bat test
./gradlew bootJar              # сборка backend JAR
cd simulator
./gradlew test                 # отдельные тесты движка симуляции
```

Интеграционные тесты PostgreSQL используют Testcontainers и требуют запущенный Docker; они поднимают отдельный временный PostgreSQL 15. Поэтому `gradlew test` запускайте при работающем Docker.
Dockerfile собирает backend JAR без запуска тестов (`-x test`); перед защитой
запускайте тесты отдельно.

Полный интерактивный контракт доступен в Swagger после запуска backend:
Swagger UI: http://localhost:8080/swagger-ui.html
OpenAPI JSON: http://localhost:8080/v3/api-docs

## Откуда фронт берёт данные

Два набора адресов.

**Живая симуляция** (`/api/v1/...`, ответ в конверте `{ success, data, error }`, контракт Вани, docs/08):

| Что на экране | Запрос |
|---|---|
| Время симуляции, скорость, счётчики | `GET /api/v1/state` |
| 69 печей: состояние, % готовности, отклонение | `GET /api/v1/ovens?zoom=battery` |
| Четыре машины (КВ, ДСМ, ТВ, УВ) и их положение | `GET /api/v1/machines` |
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
  entity/       таблицы БД (Equipment, CokeOvens, ScheduleOperation, ScheduleRun, ScheduleSettings, Notification, AuditLog, ...)
  repository/   доступ к БД (Spring Data JPA)
  controller/   REST: /api/... (БД) и SimulatorController (/api/v1, симуляция)
  service/      SimulatorService, ScheduleRunService, ScheduleService, DirectoryCsvService, AuditService
  dto/          ApiResponse (конверт ответа симулятора)
  exception/    единый обработчик ошибок REST API
  config/       CorsConfig
simulator/      симулятор батареи (код Вани, отдельная Gradle-сборка)
```

## Как это связано

- `SimulatorService` держит один `Simulator` в памяти и раз в секунду двигает его. Скорость x60 = 1 секунда реального
  времени равна 1 минуте симуляции. После запуска приложения симуляция на паузе, ПУСК запускает её.
- Действия оператора (пуск, пауза, сброс, отказ, настройки, формирование графика) пишутся в таблицу `audit_log`
  со временем симуляции.
- «Сформировать график» вызывает `ScheduleRunService`: B1 FIFO, B2 idle или B3 smooth строят план
  на одном порядке серийности; выбранный план устанавливается в живой симулятор и сохраняется в
  `schedule_runs` и `schedule_operations`.
- Для новых операций берётся приоритет из активных настроек (1 — обычный, 2 — высокий, 3 — срочный).
  Более высокий приоритет уменьшает допустимый порог опоздания; порядок серийности не нарушается.
- Окно смены применяется как временное ограничение начала операций: если 30-минутная операция
  не помещается до конца смены, она переносится на следующую смену.
- При переходе операции в `OVERDUE` / `IMPOSSIBLE` или при подтверждённом позднем факте создаётся
  запись в `notifications`; непрочитанные уведомления доступны через `/api/notifications/unread`.
- Метрика технологических нарушений сейчас проверяет H1 (серийность) и H4 (выдача → загрузка).
  H2, H3, H5–H9 описаны в предметной модели, но автоматическими валидаторами пока не покрыты.
- Температура и справочник берутся из БД (демо-данные): у симулятора телеметрии пока нет.

## Итоговая документация

Подробное описание архитектуры, API, PostgreSQL, Docker, тестов и ограничений: [`docs/14-итоговая-документация-проекта.md`](docs/14-итоговая-документация-проекта.md).

## Тестирование API

Интеграционные тесты PostgreSQL используют Testcontainers и требуют запущенный Docker. Они поднимают отдельную временную БД PostgreSQL 15, не затрагивая рабочий том приложения. Подробности: [документация тестирования API](docs/13-тестирование-api.md).


- Backend unit/controller tests: `./gradlew test` (Windows: `./gradlew.bat test`).
- Docker smoke checks after `docker compose up --build -d`: `./scripts/smoke-api.ps1`.
- Матрица покрытия и границы тестирования: [`docs/13-тестирование-api.md`](docs/13-тестирование-api.md).

Контроллерные тесты используют MockMvc и Mockito; они не подменяют собой интеграционные тесты PostgreSQL. Симулятор остаётся отдельным модулем и в рамках API-доработки не изменялся.

## Дополнительные функции интерфейса (ноябрь 2026)

- В блоке временной шкалы доступен режим «Открыть полный график»: выбор даты, переход на предыдущий/следующий день, горизонтальная шкала 00:00–24:00 и список операций выбранного дня. Нажатие на незавершённую операцию открывает форму ручного переноса с обязательной причиной.
- В окне «Аналитика» добавлен экспорт температурных измерений печей: `GET /api/temperature/export/csv` и `GET /api/temperature/export/xlsx`. Оба endpoint поддерживают необязательные параметры `from`, `to` (ISO LocalDateTime) и `ovenNumber`. Источник — таблица `oven_temperature_readings`; экспорт не создаёт live-телеметрию.
