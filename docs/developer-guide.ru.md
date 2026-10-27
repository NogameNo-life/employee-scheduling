# Планировщик бригад: документация для разработчиков

Сервис `employee-scheduling` планирует, какие бригады и сотрудники работают в каждую смену на линиях фасовки.
Это один из трёх независимых бэкендов (reports, food-packaging, employee-scheduling) за общим фронтендом.
Бэкенды не вызывают друг друга, у каждого своя база данных.

Руководство пользователя: [user-guide.ru.md](user-guide.ru.md).

## Технологии

- Java 21, Spring Boot 3.5 (Spring MVC, Spring Data JPA, Hibernate 6.6), Lombok, springdoc (Swagger UI).
- Spring Boot 4 пока не используется: он требует Hibernate 7 и Jackson 3, а TABU2 работает на SQL Server 2012.
- MS SQL Server, схема `dbo`, два источника данных (см. ниже).
- Фронтенд: `src/main/resources/static/index.html` + `app.js` (jQuery, Bootstrap, js-joda, vis-timeline).
- Сборка: Maven; Docker: `Dockerfile` (multi-stage, fat jar) и `docker-compose.yaml`.

## Запуск

1. Скопировать `env.template` в `.env` и заполнить значения.
2. Локально:
   ```shell
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```
   Профиль `local` выводит SQL в лог.
3. Открыть http://localhost:8080, Swagger UI — http://localhost:8080/swagger-ui.html.

TABU2 поддерживает только TLS 1.0, поэтому JVM нужен параметр
`-Djava.security.properties=src/main/jvm/legacy-tls.security`. `spring-boot:run` и `Dockerfile` добавляют его сами;
при ручном запуске jar его нужно указать:

```shell
mvn package
java -Djava.security.properties=src/main/jvm/legacy-tls.security -jar target/employee-scheduling.jar
```

Docker: `docker compose up -d --build` (настройки из `.env`).

Приложение запускается и без доступных баз данных: ошибки пишутся в лог, страница открывается.
Если недоступна только TABU2, вместо ФИО показывается табельный номер.

### Переменные окружения

| Переменная | Назначение |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` — вывод SQL; пусто для production |
| `HTTP_PORT` | Порт HTTP (по умолчанию 8080) |
| `APP_TIME_ZONE` | Часовой пояс завода для «сегодня» и «текущего месяца» (по умолчанию `Europe/Minsk`) |
| `MES_DB_URL`, `MES_DB_USERNAME`, `MES_DB_PASSWORD` | База MES (таблицы `PLR_*`) |
| `TABU2_DB_URL`, `TABU2_DB_USERNAME`, `TABU2_DB_PASSWORD` | База TABU2 (`BD_CARD`) |
| `CORS_ORIGIN` | Разрешённые адреса фронтенда через запятую; пусто — без CORS |

Локально Spring также читает `.env` (`spring.config.import=optional:file:.env[.properties]`).

## Структура пакетов

`org.acme.employeescheduling`:

| Пакет | Содержимое |
| --- | --- |
| `config` | `MesDataSourceConfig` (основной), `TabuDataSourceConfig`, `ClockConfig`, `CorsConfig`, `SessionHeader` |
| `entity.plr`, `repository.plr` | JPA-сущности и репозитории базы MES |
| `entity.tabu`, `repository.tabu` | JPA-сущности и репозитории базы TABU2 |
| `domain` | Доменная модель: простые классы без аннотаций фреймворков (кроме Jackson) |
| `service` | Загрузка данных, кэш, создание версий, редактор графика |
| `repository` | `CrewScheduleRepository` — версии графика в памяти |
| `controller` | `CrewScheduleController` — REST API |
| `exception` | `ScheduleException`, `ErrorInfo`, `GlobalExceptionHandler` |

Правило: JPA-сущности (слой БД) отделены от доменных классов (`domain/`). Сущности и репозитории каждой базы
лежат в своих пакетах, иначе их не найдёт конфигурация источника данных.

## База данных

| Таблица | Сущность | Смысл |
| --- | --- | --- |
| `PLR_BR` | `PlrBr` | Бригада. `BRID` — номер бригады, `OBJ` — подразделение |
| `PLR_ROLE` | `PlrRole` | Роль. `RLID` — номер роли |
| `PLR_PLINES` | `PlrLines` | Линия |
| `BD_CARD` (TABU2) | `BdCard` | Сотрудник. `TABN` — табельный номер, `FIO` — ФИО |
| `PLR_TBBR` | `PlrTbbr` | Сотрудник → бригада (`TABN`, `BRID`), `F_ID` — ключ, `F_DEL` — удалён |
| `PLR_RLTB` | `PlrRltb` | Сотрудник → роль (`TABN`, `RLID`) |
| `PLR_RLBR` | `PlrRlbr` | Состав бригады: бригада, роль, `NPERS` — сколько человек нужно в смену |

- Hibernate не меняет схему базы данных (нет `hbm2ddl`).
- В сущностях нет JPA-связей, только целочисленные id. `CrewDataLoader` читает каждую таблицу один раз
  (6 запросов на подразделение) и соединяет данные в памяти, без N+1.
- Вместо `IN (:ids)` используются подзапросы: SQL Server допускает не более 2100 параметров в запросе.
- Сотрудники и их бригады берутся из `PLR_TBBR`; из TABU2 (`BD_CARD`) — только ФИО.
- `CrewDataCache` хранит загруженные данные (`CrewData`) по подразделениям и список линий.

## Доменная модель (`domain/`)

| Класс | Описание |
| --- | --- |
| `Line` | Линия |
| `Crew` | Бригада. `staffCount` — сумма `NPERS` из `PLR_RLBR`; список `employees` (`@JsonIgnore`, т. к. бригада есть в каждой позиции) |
| `Role` | Роль |
| `Employee` | Сотрудник: `fullName` (`BD_CARD.FIO` без пробелов по краям), `crew` (на начало месяца), `crewChanges`, `absences`, `roles` |
| `CrewChange` | Запись (record): с даты `from` сотрудник в бригаде `crew` |
| `Absence`, `AbsenceType` | Отсутствие с `from` по `to` включительно; `VACATION`, `SICK_LEAVE`, `DISMISSAL` (`to = null`, без конца), `OTHER` |
| `ShiftType` | `DAY` (08:00–20:00) или `NIGHT` (20:00–08:00 следующего дня) |
| `CrewShift` | Смена бригады: дата, тип, линия, бригада, `pinned`, `absentEmployees`, `extraEmployees`, `composition` (число людей по ролям только для этой смены, `null` — состав бригады) |
| `Position` | Место в смене бригады для одной роли (по одному на человека из `NPERS`): `employee` (может быть `null`), `pinned` |
| `CrewSchedule` | Версия графика: месяц, линии, бригады, роли, сотрудники, состав (`@JsonIgnore`), смены бригад, позиции |

### Люди смены: состав по умолчанию + изменения

Id смены бригады — `дата-ТИП-idЛинии`, поэтому на одну смену линии приходится одна бригада.

Люди смены = члены бригады на дату смены **минус** `absentEmployees` **плюс** `extraEmployees`
(замены из других бригад), **без** сотрудников, у которых в этот день `Absence`. Хранятся только отличия и только
для этой смены. Поэтому:

- перевод сотрудника в другую бригаду сразу действует на все смены обеих бригад (с даты перевода);
- отпуск или больничный действует на все смены периода, в том числе добавленные позже;
- изменения одной смены не затрагивают другие дни.

Член бригады на дату: `Crew.employees` содержит всех, кто в бригаде хотя бы один день месяца
(`Employee.getCrewsOfMonth`), а `CrewShift.isMember` дополнительно проверяет `Employee.getCrewOn(date)`.
Отсутствие относится к смене по дате её **начала** (`Absence.covers(date)`).

В JSON смены: `employeeIds` (кто работает), `absentEmployeeIds`, `extraEmployeeIds`, `unavailableEmployeeIds`
(члены и замены на отсутствии), `staffCount` (нужно людей в этой смене), `roleCounts` (id роли → число, `null` —
состав бригады). В JSON сотрудника: `crew`, `crewChanges` (`from`, `crew`), `absences` (`id`, `type`, `from`, `to`).

### Версии графика

- У каждого пользователя (заголовок `X-Session-Id`; без заголовка — `default_session_id`) своя версия
  `CrewSchedule` в `CrewScheduleRepository`. Версии хранятся **только в памяти** и удаляются после 24 часов
  без обращений (кроме версии по умолчанию).
- Версия по умолчанию (текущий месяц, без смен) создаётся при запуске (`CrewScheduleSessionService.onStart`).
  Сессия без версии получает её копию.
- Бригады, сотрудники и состав — собственные копии версии (`CrewScheduleFactory.createEmpty/copyOf`),
  поэтому изменения не затрагивают другие версии и кэш.
- У каждой версии свой id. `CrewScheduleRepository.update` меняет только ту версию, которую ему передали,
  поэтому поздний запрос не перезапишет более новую версию.
- Фронтенд хранит id сессии в `sessionStorage` (ключ `solverSessionId`), т. е. у каждой вкладки своя версия.

### Редактирование: `CrewScheduleEditor`

Чистый класс, работает с одним `CrewSchedule`. `CrewScheduleSessionService.edit` вызывает его внутри
`synchronized(schedule)` и превращает исключения в HTTP-ответы:

| Исключение | HTTP |
| --- | --- |
| `NoSuchElementException` (нет такой смены, сотрудника, бригады, линии) | 404 |
| `IllegalArgumentException` (неверный запрос, дата не в месяце версии) | 400 |
| `IllegalStateException` (конфликт с графиком) | 409 |

Правила:

- на одну смену линии — одна бригада;
- бригада не может работать на двух линиях одновременно;
- сотрудник не может работать в двух сменах одновременно (в т. ч. на замене);
- дата должна быть в месяце версии;
- закреплённую смену нельзя перенести или удалить (людей в ней менять можно);
- при переносе смены отсутствующие и замены сохраняются, только если смена остаётся в то же время с той же
  бригадой (перенос на другую линию);
- при переводе сотрудника в другую бригаду (`moveEmployee`, с даты `from`; 1-е число — на весь месяц) более
  поздние переводы удаляются, переводы в ту же бригаду не хранятся; отметки «отсутствует» в сменах, где он больше
  не член бригады, и «замена» в сменах, где он теперь член бригады, удаляются; если в смену новой бригады он уже
  работает в другом месте, он отмечается как отсутствующий;
- отсутствия одного сотрудника не пересекаются (409), должны задевать месяц версии (400); у `DISMISSAL` поле `to`
  игнорируется; при добавлении отсутствия сотрудник снимается с позиций этих смен;
- в отсутствие нельзя добавить сотрудника в смену или поставить на замену (409);
- `setShiftComposition`: роль должна быть в версии, число 0–50; число, равное составу бригады, не хранится;
  позиции смены создаются заново, позиции с тем же id сохраняют сотрудника; при переносе смены состав сохраняется
  по тому же правилу, что и отсутствующие.

`LineSchedule` собирает смены по линиям и дням: дневная и ночная смена дня — `Pair<CrewShift, CrewShift>`
(commons-lang3) в `LineSchedule.LineDay`.

«Сегодня» и «текущий месяц» берутся из бина `Clock` (`ClockConfig`, `app.time-zone`).

## REST API

Все пути начинаются с `/crew-schedules` и работают с версией вызывающего (`X-Session-Id`).
Изменения смен возвращают весь график по линиям (`RotationResponse`: `sessionId`, `versionId`, `month`, `lines`).

| Метод | Путь | Тело | Назначение |
| --- | --- | --- | --- |
| GET | `/crew-schedules` | | Вся версия: смены бригад, позиции, сотрудники |
| GET | `/crew-schedules/rotation` | | Дневная и ночная бригада для каждой линии и дня |
| GET | `/crew-schedules/crews` | | Бригады с линиями, ролями и числом людей (только чтение) и сотрудниками |
| GET | `/crew-schedules/lines` | | Линии; работает без версии |
| POST | `/crew-schedules/init?month=yyyy-MM` | | Новая пустая версия на месяц (без `month` — текущий) |
| POST | `/crew-schedules/crew-shifts` | `{date, type, lineId, crewId}` | Поставить бригаду на смену |
| PUT | `/crew-schedules/crew-shifts/{id}` | `{date, type, lineId, crewId}` | Перенести смену или поменять бригаду |
| DELETE | `/crew-schedules/crew-shifts/{id}` | | Удалить смену |
| PUT | `/crew-schedules/crew-shifts/{id}/pinned` | `{pinned}` | Закрепить / открепить |
| PUT | `/crew-schedules/crew-shifts/{id}/employees/{employeeId}` | | Сотрудник работает в этой смене (вернуть отсутствующего или добавить замену) |
| DELETE | `/crew-schedules/crew-shifts/{id}/employees/{employeeId}` | | Сотрудник не работает в этой смене |
| POST | `/crew-schedules/crew-shifts/{id}/replacements` | `{employeeId, substituteId}` | Заменить сотрудника в этой смене одним шагом |
| PUT | `/crew-schedules/crew-shifts/{id}/composition` | `{roles: [{roleId, count}]}` | Число людей по ролям только для этой смены |
| DELETE | `/crew-schedules/crew-shifts/{id}/composition` | | Вернуть состав бригады |
| PUT | `/crew-schedules/employees/{id}/crew` | `{crewId, from?}` | Перевести сотрудника в другую бригаду с даты `from` (без неё — весь месяц); возвращает список бригад |
| DELETE | `/crew-schedules/employees/{id}/crew-changes/{from}` | | Отменить перевод с даты; возвращает список бригад |
| POST | `/crew-schedules/employees/{id}/absences` | `{type, from, to}` | Отпуск, больничный, увольнение (`to` не нужен), другое |
| DELETE | `/crew-schedules/employees/{id}/absences/{absenceId}` | | Удалить отсутствие |

Ошибки: `ScheduleException` → JSON `ErrorInfo` (`sessionId`, `message`) с кодом 400 / 404 / 409.

## Фронтенд (`static/app.js`)

- Две диаграммы vis-timeline:
  - **«By line»** (`byLineTimeline`): строки — линии, элементы — смены бригад (`GET /crew-schedules/rotation`).
    Двойной щелчок по пустому месту — добавить (окно `crewShiftModal`), двойной щелчок по смене — поменять бригаду,
    перетаскивание — перенести (`snapToShiftStart`, привязка к 08:00/20:00), × — удалить, замок — закрепить.
    Щелчок по названию бригады или по числу людей открывает окно людей смены (`shiftPeopleModal`,
    `openShiftPeopleDialog` / `renderShiftPeopleDialog`), в нём же число людей по ролям (`renderShiftComposition`,
    `saveShiftComposition`).
  - **«By employee»** (`byEmployeeTimeline`): сотрудники по бригадам, данные из `GET /crew-schedules`.
    Двойной щелчок — окно `shiftEmployeeModal` (снять, заменить, вернуть, добавить). Отсутствия — фоновые
    элементы (`type: "background"`, класс `absence`). Щелчок по ФИО (`props.what === "group-label"`) — окно
    сотрудника.
- Окно сотрудника (`employeeModal`, `openEmployeeDialog` / `renderEmployeeDialog`): переводы с даты и отсутствия.
  `crewOn` и `absenceOn` повторяют `Employee.getCrewOn` / `getAbsenceOn` на клиенте (даты ISO сравниваются как строки).
- Список бригад (`renderCrews`) из `GET /crew-schedules/crews`; смена бригады сотрудника — `moveEmployee`.
- После каждого изменения `onRosterChanged` перерисовывает график по линиям и заново загружает
  `GET /crew-schedules` и `GET /crew-schedules/crews`. Открытое окно людей смены перерисовывается после загрузки.
- Время смены по месту щелчка определяет `slotOf` (08:00–20:00 — `DAY`, иначе `NIGHT` дня начала).
- Текст вставляется через jQuery (`.text()`), поэтому в vis-timeline отключена встроенная XSS-фильтрация.

## Тесты

```shell
mvn test
```

Тесты не требуют базы данных:

| Тест | Что проверяет |
| --- | --- |
| `CrewScheduleEditorTest` | Правила редактора |
| `CrewScheduleFactoryTest` | Создание и копирование версий |
| `CrewScheduleRepositoryTest` | Хранение версий |
| `CrewRosterServiceTest` | График по линиям, список бригад |
| `CrewDataLoaderTest` | Загрузка и соединение таблиц |
| `LineScheduleJsonTest` | JSON графика по линиям |
| `CrewScheduleControllerTest` | REST (`@WebMvcTest`, кэш замокан) |

Фронтенд автотестами не покрыт.

## Что пока не сделано

- Сохранение графика бригад и версий в базе данных (таблица будет создана позже).
- Загрузка последней недели предыдущего месяца как закреплённых позиций (нужно для правила «не больше 6 дней подряд»).
- Отпуска, больничные и обмен сменами из базы данных: таблицы нет, отсутствия вводятся вручную и хранятся
  только в версии.
- Ручное назначение сотрудников на позиции (API и интерфейс) и его сохранение.
- Автоматическая расстановка (решателя нет: Timefold удалён, последняя версия с ним — коммит `9cb2303`).
- Выгрузка в «Табельный учёт» и расчёт выработки по операциям.

## Открытые вопросы

- Время смен: принято 08:00–20:00 и 20:00–08:00 (12 ч).
- Может ли сотрудник работать в другой бригаде (на замене)? Сейчас может.
- Всегда ли бригада привязана к одной линии?
