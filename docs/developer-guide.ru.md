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
| `MES_DB_URL`, `MES_DB_USERNAME`, `MES_DB_PASSWORD` | База MES2 (таблицы `PLR_*`) |
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

Все таблицы `PLR_*` — в базе MES2 (источник данных MES), `BD_CARD` — в TABU2.

| Таблица | Сущность | Смысл |
| --- | --- | --- |
| `PLR_BR` | `PlrBr` | Бригада. `BRID` — номер, `OBJ` — подразделение, `NAME`, `NTB` — сколько человек нужно в смену, `KC` — линия бригады (`PLR_LC.KC`) |
| `PLR_LC` | `PlrLc` | Линии планировщика. `KC` — код линии («БерЦГС1»), `KRC` — старый код («170610060000»), `DTBEG`..`DTEND` (datetime, `NULL` — без границы) — период действия, `F_DEL` — удалена. Названия нет |
| `PLR_PLINES` | `PlrLines` | Только для названия линии: `SNM` по `KRC` (по строке на тип упаковки, берётся первое название) |
| `PLR_TB` | `PlrTb` | Сотрудники бригады на месяц: `BRID`, `TABN`, `GGMM` (месяц `yyyyMM`), `POS` (порядок в бригаде), `F_GUID` — ключ, `F_DEL` — удалён |
| `PLR_SM` | `PlrSm` | Типы смен: `SM` (`1` — день, `2` — ночь, CHAR), `TMNR` — начало, `TMKR` — конец (N5,2 «часы.минуты»), `SNG` — коды через `;` |
| `BD_CARD` (TABU2) | `BdCard` | Сотрудник. `TABN` — табельный номер, `FIO` — ФИО |

- Hibernate не меняет схему базы данных (нет `hbm2ddl`). Столбцы `F_TM` (rowversion) не отображаются.
- В сущностях нет JPA-связей, только id. `CrewDataLoader` читает каждую таблицу один раз и соединяет данные
  в памяти, без N+1.
- Вместо `IN (:ids)` используются подзапросы: SQL Server допускает не более 2100 параметров в запросе.
- Из TABU2 (`BD_CARD`) берётся только ФИО. Если TABU2 недоступна или сотрудника там нет, ФИО — табельный номер.

### Линии

- Id линии — `PLR_LC.KC` без пробелов, `krc` — `PLR_LC.KRC`. Название — `PLR_PLINES.SNM` по `KRC`, иначе `KC`.
- Линия активна в месяце, если `F_DEL = 0`, `KC` не пустой и период `DTBEG`..`DTEND` пересекается с месяцем.
  `DTBEG`/`DTEND` читаются как даты (время отбрасывается).
- `CrewDataCache.getLine(kc)` и `getLineByKrc(krc)` ищут линию в обе стороны (выработка будет приходить по `KRC`),
  `getLines(month)` — активные линии месяца по названию.
- `KC` содержит кириллицу, поэтому id смены бригады (`2026-11-03-DAY-БерЦГС1`) в URL кодируется
  (`encodeURIComponent` в `app.js`).

### Сотрудники бригад (`PLR_TB`)

`CrewDataLoader.load(department, month)` берёт строки `PLR_TB` так:

1. строки месяца (`GGMM = yyyyMM`);
2. если их нет — строки последнего более раннего месяца, где строки есть;
3. если ни одного месяца нет — строки без месяца (`GGMM IS NULL`; сейчас `GGMM` ещё не заполнен).

Случаи 2 и 3 пишутся в лог. Если сотрудник в нескольких бригадах, берётся первая строка (предупреждение в логе).
Сотрудники бригады сортируются по `POS` (без позиции — в конце), затем по ФИО (`Employee.BY_POSITION_AND_NAME`).

`CrewDataCache` хранит `CrewData` по подразделению и месяцу; `POST /init` перечитывает линии, смены и бригады месяца.

### Время смен (`PLR_SM`)

- `ShiftType`: `DAY` (код `1`), `NIGHT` (код `2`); `fromCode` / `getCode`. Код нужен только для БД,
  id смены бригады содержит `DAY` / `NIGHT`.
- `TMNR` / `TMKR` переводит в `LocalTime` один метод `ShiftTime.fromHoursMinutes`: 8.30 = 08:30. Минуты 60 и больше,
  часы 24 и больше, отрицательное число — `IllegalArgumentException`. Если окажется, что формат другой
  (например 8.50 = 08:30), менять нужно только его.
- `CrewDataCache.refreshShifts` при запуске и в `POST /init` читает `PLR_SM`. Если таблица недоступна, нет строки
  для типа или строку нельзя прочитать — берётся 08:00–20:00 / 20:00–08:00 и пишется предупреждение.
- Смена, у которой конец не позже начала, заканчивается на следующий день. Часы смены (`hours`) считаются по её
  времени, а не константа 12.

## Доменная модель (`domain/`)

| Класс | Описание |
| --- | --- |
| `Line` | Линия: `id` (`KC`), `krc`, `name` |
| `Crew` | Бригада: `staffCount` (`PLR_BR.NTB`), `lineId` (`PLR_BR.KC`, может быть `null`; только информация — бригаду можно поставить на любую линию), список `employees` (`@JsonIgnore`, т. к. бригада есть в каждой смене) |
| `Employee` | Сотрудник: `fullName` (`BD_CARD.FIO` без лишних пробелов), `position` (`PLR_TB.POS`), `crew` (на начало месяца), `crewChanges`, `absences` |
| `CrewChange` | Запись (record): с даты `from` сотрудник в бригаде `crew` |
| `Absence`, `AbsenceType` | Отсутствие с `from` по `to` включительно; `VACATION`, `SICK_LEAVE`, `DISMISSAL` (`to = null`, без конца), `OTHER` |
| `ShiftType` | `DAY` / `NIGHT` и их коды в `PLR_SM.SM` |
| `ShiftTime` | Запись (record): `type`, `start`, `end`, `sng` из `PLR_SM`; `startOn`, `endOn`, `hours` |
| `CrewShift` | Смена бригады: дата, `time` (`ShiftTime` версии), линия, бригада, `pinned`, `absentEmployees`, `extraEmployees` |
| `CrewSchedule` | Версия графика: месяц, линии, `shifts` (время смен), бригады, сотрудники, смены бригад |

### Люди смены: члены бригады + изменения

Id смены бригады — `дата-ТИП-KC` (например `2026-11-03-NIGHT-БерЦГС1`), поэтому на одну смену линии приходится
одна бригада.

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
(члены и замены на отсутствии), `staffCount` (= `crew.staffCount`), `start`, `end`, `hours`.
В JSON сотрудника: `position`, `crew`, `crewChanges` (`from`, `crew`), `absences` (`id`, `type`, `from`, `to`).

### Версии графика

- У каждого пользователя (заголовок `X-Session-Id`; без заголовка — `default_session_id`) своя версия
  `CrewSchedule` в `CrewScheduleRepository`. Версии хранятся **только в памяти** и удаляются после 24 часов
  без обращений (кроме версии по умолчанию).
- Версия по умолчанию (текущий месяц, без смен) создаётся при запуске (`CrewScheduleSessionService.onStart`).
  Сессия без версии получает её копию.
- Бригады и сотрудники — собственные копии версии (`CrewScheduleFactory.createEmpty/copyOf`),
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
  игнорируется;
- в отсутствие нельзя добавить сотрудника в смену или поставить на замену (409);
- время смены бригады берётся из `shifts` версии (`CrewSchedule.getShift(type)`).

`LineSchedule` собирает смены по линиям и дням: дневная и ночная смена дня — `Pair<CrewShift, CrewShift>`
(commons-lang3) в `LineSchedule.LineDay`.

«Сегодня» и «текущий месяц» берутся из бина `Clock` (`ClockConfig`, `app.time-zone`).

## REST API

Все пути начинаются с `/crew-schedules` и работают с версией вызывающего (`X-Session-Id`).
Изменения смен возвращают весь график по линиям (`RotationResponse`: `sessionId`, `versionId`, `month`, `shifts`,
`lines`).
Примеры запросов и ответов: [rest-api.ru.md](rest-api.ru.md).

| Метод | Путь | Тело | Назначение |
| --- | --- | --- | --- |
| GET | `/crew-schedules` | | Вся версия: линии, время смен, бригады, сотрудники, смены бригад |
| GET | `/crew-schedules/rotation` | | Дневная и ночная бригада для каждой линии и дня |
| GET | `/crew-schedules/crews` | | Бригады с линиями (где работают в версии) и сотрудниками |
| GET | `/crew-schedules/lines` | | Линии текущего месяца; работает без версии |
| POST | `/crew-schedules/init?month=yyyy-MM` | | Новая пустая версия на месяц (без `month` — текущий) |
| POST | `/crew-schedules/crew-shifts` | `{date, type, lineId, crewId}` | Поставить бригаду на смену |
| PUT | `/crew-schedules/crew-shifts/{id}` | `{date, type, lineId, crewId}` | Перенести смену или поменять бригаду |
| DELETE | `/crew-schedules/crew-shifts/{id}` | | Удалить смену |
| PUT | `/crew-schedules/crew-shifts/{id}/pinned` | `{pinned}` | Закрепить / открепить |
| PUT | `/crew-schedules/crew-shifts/{id}/employees/{employeeId}` | | Сотрудник работает в этой смене (вернуть отсутствующего или добавить замену) |
| DELETE | `/crew-schedules/crew-shifts/{id}/employees/{employeeId}` | | Сотрудник не работает в этой смене |
| POST | `/crew-schedules/crew-shifts/{id}/replacements` | `{employeeId, substituteId}` | Заменить сотрудника в этой смене одним шагом |
| PUT | `/crew-schedules/employees/{id}/crew` | `{crewId, from?}` | Перевести сотрудника в другую бригаду с даты `from` (без неё — весь месяц); возвращает список бригад |
| DELETE | `/crew-schedules/employees/{id}/crew-changes/{from}` | | Отменить перевод с даты; возвращает список бригад |
| POST | `/crew-schedules/employees/{id}/absences` | `{type, from, to}` | Отпуск, больничный, увольнение (`to` не нужен), другое |
| DELETE | `/crew-schedules/employees/{id}/absences/{absenceId}` | | Удалить отсутствие |

Ошибки: `ScheduleException` → JSON `ErrorInfo` (`sessionId`, `message`) с кодом 400 / 404 / 409.

## Фронтенд (`static/app.js`)

- Две диаграммы vis-timeline:
  - **«By line»** (`byLineTimeline`): строки — линии, элементы — смены бригад (`GET /crew-schedules/rotation`).
    Двойной щелчок по пустому месту — добавить (окно `crewShiftModal`), двойной щелчок по смене — поменять бригаду,
    перетаскивание — перенести (`snapToShiftStart`, привязка к началу ближайшей смены), × — удалить,
    замок — закрепить. Щелчок по названию бригады или по числу людей открывает окно людей смены
    (`shiftPeopleModal`, `openShiftPeopleDialog` / `renderShiftPeopleDialog`).
  - **«By employee»** (`byEmployeeTimeline`): сотрудники по бригадам (по позиции, затем по ФИО —
    `byPositionAndName`), данные из `GET /crew-schedules`; часы за месяц — сумма `hours` смен.
    Двойной щелчок — окно `shiftEmployeeModal` (снять, заменить, вернуть, добавить). Отсутствия — фоновые
    элементы (`type: "background"`, класс `absence`). Щелчок по ФИО (`props.what === "group-label"`) — окно
    сотрудника.
- Окно сотрудника (`employeeModal`, `openEmployeeDialog` / `renderEmployeeDialog`): переводы с даты и отсутствия.
  `crewOn` и `absenceOn` повторяют `Employee.getCrewOn` / `getAbsenceOn` на клиенте (даты ISO сравниваются как строки).
- Список бригад (`renderCrews`) из `GET /crew-schedules/crews`; смена бригады сотрудника — `moveEmployee`.
- После каждого изменения `onRosterChanged` перерисовывает график по линиям и заново загружает
  `GET /crew-schedules` и `GET /crew-schedules/crews`. Открытое окно людей смены перерисовывается после загрузки.
- Время смен приходит с сервера (`shifts` в `RotationResponse` и в `GET /crew-schedules`, `setShiftTimes`);
  до первого ответа — 08:00–20:00 и 20:00–08:00. `slotOf` определяет смену по месту щелчка (смена, в интервал
  которой попадает время; ночная относится к дню начала), `shiftName` — подпись смены с её временем.
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
| `CrewDataLoaderTest` | Бригады из `PLR_BR`, выбор строк `PLR_TB` по месяцу, порядок сотрудников, ФИО |
| `CrewDataCacheTest` | Линии `PLR_LC` (KC ↔ KRC, названия, периоды), время смен из `PLR_SM` |
| `ShiftTimeTest` | Перевод «часы.минуты», конец смены на следующий день, коды `ShiftType` |
| `LineScheduleJsonTest` | JSON графика по линиям |
| `CrewScheduleControllerTest` | REST (`@WebMvcTest`, кэш замокан) |

Фронтенд автотестами не покрыт.

## Что пока не сделано

- Генератор графика бригад и предупреждения о правиле отдыха (не меньше 12 часов между сменами).
- Сохранение и загрузка плана: `PLR_SNAR` (смены бригад), `PLR_RGRT` (часы сотрудников); часы по сотрудникам,
  один сотрудник на двух линиях за день.
- Отпуска и больничные из табеля (`BD_RGR`): сейчас отсутствия вводятся вручную и хранятся только в версии.
- Выработка по операциям (`PLR_PLINV`), последняя неделя предыдущего месяца для правила отдыха.
- API из технического задания (`GET /init?ggmm=&obj=`, `POST /save`, ...): не решено, переименовывать ли методы.
- Решателя нет: Timefold удалён, последняя версия с ним — коммит `9cb2303`.

## Открытые вопросы

- `PLR_SM.TMNR` / `TMKR`: принято «часы.минуты» (8.30 = 08:30). В реальных строках только целые часы
  (8.00, 20.00), поэтому формат минут не подтверждён.
- `PLR_TB.GGMM` и `POS` пока не заполнены: используются строки без месяца, порядок — по ФИО.
- Может ли бригада работать на чужой линии (в `PLR_SNAR` нет столбца линии)? Сейчас может.
- Кто создаёт `SNID` (код наряда)?
