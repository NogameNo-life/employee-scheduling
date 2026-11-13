# Планировщик бригад: REST API

Методы `CrewScheduleController` с примерами запросов и ответов.
Краткая таблица методов и устройство сервиса: [developer-guide.ru.md](developer-guide.ru.md).
Swagger UI: http://localhost:8080/swagger-ui.html.

## Общие правила

- Все пути начинаются с `/crew-schedules`.
- Заголовок `X-Session-Id` выбирает версию графика пользователя. Без него (или пустой) — `default_session_id`.
  Сессия без версии получает копию версии по умолчанию (текущий месяц, без смен бригад).
- Изменения хранятся только в версии (в памяти), в БД ничего не пишется. Версия удаляется после 24 ч простоя.
- Тело запроса — JSON (`Content-Type: application/json`).
- Даты — `yyyy-MM-dd`, месяц — `yyyy-MM`, дата и время — `yyyy-MM-ddTHH:mm:ss`.
- Тип смены `type`: `DAY` (08:00–20:00) или `NIGHT` (20:00–08:00 следующего дня).
- Id смены бригады: `{date}-{type}-{lineId}`, например `2026-11-03-DAY-101`.
- Id позиции: `{id смены}-{roleId}-{номер}`, например `2026-11-03-DAY-101-5-0`.
- Id сотрудника — табельный номер (TABN), id бригады — BRID, id роли — RLID.

В примерах ниже: месяц ноябрь 2026, линия `101` «Линия 1», бригады 1 и 2, роли 5 «Фасовщик» и 7 «Наладчик».

### Ошибки

Код 400 (неверный запрос), 404 (не найдено), 409 (конфликт с графиком), тело — `ErrorInfo`:

```json
{
  "sessionId": "a1b2c3",
  "message": "Line Линия 1 already has crew Бригада 2 on 2026-11-03, DAY shift."
}
```

Если версии ещё нет вообще (не удалось загрузить данные при запуске), любой метод, кроме `GET /lines` и `POST /init`,
вернёт 404 с сообщением `No schedule version yet. Press New empty month.`

### Ответ `RotationResponse`

Большинство изменений возвращают весь график по линиям. В `lines` есть все активные линии, также без смен
(тогда `days` пустой). В `days` только дни, где есть хотя бы одна смена; `dayShift` / `nightShift` — `null`,
если в эту смену бригады нет.

```json
{
  "sessionId": "a1b2c3",
  "versionId": "6f1d0c5e-3a7b-4a59-9c1e-2b8f0e4d7a10",
  "month": "2026-11",
  "lines": [
    {
      "line": { "id": "101", "name": "Линия 1" },
      "days": [
        {
          "date": "2026-11-03",
          "dayShift": {
            "id": "2026-11-03-DAY-101",
            "date": "2026-11-03",
            "type": "DAY",
            "line": { "id": "101", "name": "Линия 1" },
            "crew": { "id": 1, "name": "Бригада 1", "staffCount": 3 },
            "pinned": false,
            "employeeIds": [1002, 1003, 2001],
            "absentEmployeeIds": [1001],
            "extraEmployeeIds": [2001],
            "unavailableEmployeeIds": [1004],
            "roleCounts": null,
            "staffCount": 3,
            "start": "2026-11-03T08:00:00",
            "end": "2026-11-03T20:00:00"
          },
          "nightShift": null
        }
      ]
    },
    {
      "line": { "id": "102", "name": "Линия 2" },
      "days": []
    }
  ]
}
```

Поля смены бригады (`CrewShift`):

| Поле | Значение |
| --- | --- |
| `employeeIds` | Кто работает в смене: члены бригады на эту дату без снятых, плюс замены, без отсутствующих |
| `absentEmployeeIds` | Члены бригады, снятые только с этой смены |
| `extraEmployeeIds` | Сотрудники других бригад, добавленные в эту смену (замены) |
| `unavailableEmployeeIds` | Члены бригады и замены, которые не работают из-за отсутствия (отпуск, больничный, ...) |
| `roleCounts` | Роль → число людей только для этой смены; `null` — состав бригады (PLR_RLBR) |
| `staffCount` | Сколько людей нужно в смене: из `roleCounts` или `crew.staffCount` |
| `pinned` | Закреплённую смену нельзя перенести или удалить |

### Ответ `CrewDetails[]`

Список бригад (по номеру) с линиями, где бригада работает в этой версии, составом по ролям (только чтение,
по имени роли) и сотрудниками (по ФИО). `crew` сотрудника — бригада на начало месяца, переводы — в `crewChanges`.
Сотрудник попадает в список бригады из своего `crew`.

```json
[
  {
    "crew": { "id": 1, "name": "Бригада 1", "staffCount": 3 },
    "lines": [{ "id": "101", "name": "Линия 1" }],
    "roles": [
      { "role": { "id": 7, "name": "Наладчик" }, "count": 1 },
      { "role": { "id": 5, "name": "Фасовщик" }, "count": 2 }
    ],
    "employees": [
      {
        "id": 1001,
        "fullName": "Иванова Анна Петровна",
        "crew": { "id": 1, "name": "Бригада 1", "staffCount": 3 },
        "crewChanges": [
          { "from": "2026-11-15", "crew": { "id": 2, "name": "Бригада 2", "staffCount": 3 } }
        ],
        "roles": [{ "id": 5, "name": "Фасовщик" }],
        "absences": [
          { "id": "0b6e2f9a-8c1d-4e57-a3f0-5d2c9b7e1a44", "type": "VACATION", "from": "2026-11-20", "to": "2026-11-30" }
        ]
      }
    ]
  }
]
```

## Чтение

### GET `/crew-schedules` — вся версия

Месяц, линии, бригады, роли, сотрудники, смены бригад и позиции (по одной на человека каждой роли в смене).
Ответ большой: каждая позиция содержит смену бригады целиком.

```http
GET /crew-schedules
X-Session-Id: a1b2c3
```

```json
{
  "month": "2026-11",
  "lines": [{ "id": "101", "name": "Линия 1" }],
  "crews": [{ "id": 1, "name": "Бригада 1", "staffCount": 3 }],
  "roles": [{ "id": 5, "name": "Фасовщик" }, { "id": 7, "name": "Наладчик" }],
  "employees": [
    {
      "id": 1002,
      "fullName": "Петров Иван Сергеевич",
      "crew": { "id": 1, "name": "Бригада 1", "staffCount": 3 },
      "crewChanges": [],
      "roles": [{ "id": 5, "name": "Фасовщик" }],
      "absences": []
    }
  ],
  "crewShifts": [
    {
      "id": "2026-11-03-DAY-101",
      "date": "2026-11-03",
      "type": "DAY",
      "line": { "id": "101", "name": "Линия 1" },
      "crew": { "id": 1, "name": "Бригада 1", "staffCount": 3 },
      "pinned": false,
      "employeeIds": [1002, 1003, 2001],
      "absentEmployeeIds": [1001],
      "extraEmployeeIds": [2001],
      "unavailableEmployeeIds": [],
      "roleCounts": null,
      "staffCount": 3,
      "start": "2026-11-03T08:00:00",
      "end": "2026-11-03T20:00:00"
    }
  ],
  "positions": [
    {
      "id": "2026-11-03-DAY-101-5-0",
      "crewShift": { "id": "2026-11-03-DAY-101", "...": "как в crewShifts" },
      "role": { "id": 5, "name": "Фасовщик" },
      "pinned": false,
      "employee": null,
      "date": "2026-11-03",
      "crew": { "id": 1, "name": "Бригада 1", "staffCount": 3 },
      "shiftType": "DAY"
    }
  ]
}
```

`employee` позиции пока всегда `null`: назначение сотрудников на позиции ещё не сделано.

### GET `/crew-schedules/rotation` — график бригад по линиям

```http
GET /crew-schedules/rotation
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse).

### GET `/crew-schedules/crews` — бригады и сотрудники

```http
GET /crew-schedules/crews
X-Session-Id: a1b2c3
```

Ответ: [`CrewDetails[]`](#ответ-crewdetails).

### GET `/crew-schedules/lines` — линии

Работает без версии и без заголовка (линии загружаются отдельно при запуске). Отсортированы по имени.

```json
[
  { "id": "101", "name": "Линия 1" },
  { "id": "102", "name": "Линия 2" }
]
```

## Версия

### POST `/crew-schedules/init?month=yyyy-MM` — новая пустая версия

Перечитывает данные из БД и создаёт для сессии новую версию на месяц без смен бригад. Без `month` — текущий месяц.
Заменяется только версия этой сессии.

```http
POST /crew-schedules/init?month=2026-12
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse) с новым `versionId`, `month: "2026-12"` и пустыми `days`.

Ошибка 400: `month must be yyyy-MM, got: 12.2026`.

## Смены бригад

### POST `/crew-schedules/crew-shifts` — поставить бригаду на смену

```http
POST /crew-schedules/crew-shifts
X-Session-Id: a1b2c3
Content-Type: application/json

{ "date": "2026-11-03", "type": "DAY", "lineId": "101", "crewId": 1 }
```

Ответ: [`RotationResponse`](#ответ-rotationresponse).

| Код | Когда |
| --- | --- |
| 400 | Нет `date`, `type`, `lineId` или `crewId`; дата не в месяце версии |
| 404 | Неизвестная линия или бригада |
| 409 | На этой смене линии уже есть бригада; бригада в это время работает на другой линии |

### PUT `/crew-schedules/crew-shifts/{id}` — перенести смену или поменять бригаду

Тело такое же, как у POST: новые дата, смена, линия и бригада. Id смены меняется, если меняются дата, смена или
линия. Снятые сотрудники и замены сохраняются только при переносе на другую линию в то же время с той же бригадой.

```http
PUT /crew-schedules/crew-shifts/2026-11-03-DAY-101
X-Session-Id: a1b2c3
Content-Type: application/json

{ "date": "2026-11-04", "type": "NIGHT", "lineId": "101", "crewId": 2 }
```

Ответ: [`RotationResponse`](#ответ-rotationresponse). Ошибки — как у POST, плюс 404 (нет такой смены) и
409 (смена закреплена).

### DELETE `/crew-schedules/crew-shifts/{id}` — удалить смену

```http
DELETE /crew-schedules/crew-shifts/2026-11-03-DAY-101
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse). 404 — нет такой смены, 409 — смена закреплена.

### PUT `/crew-schedules/crew-shifts/{id}/pinned` — закрепить / открепить

```http
PUT /crew-schedules/crew-shifts/2026-11-03-DAY-101/pinned
X-Session-Id: a1b2c3
Content-Type: application/json

{ "pinned": true }
```

Ответ: [`RotationResponse`](#ответ-rotationresponse), у смены `"pinned": true`.

## Люди одной смены

Эти изменения касаются только одной смены бригады; состав бригады не меняется.

### PUT `/crew-schedules/crew-shifts/{id}/employees/{employeeId}` — сотрудник работает в смене

Член бригады, снятый со смены, снова работает в ней; сотрудник другой бригады добавляется как замена.

```http
PUT /crew-schedules/crew-shifts/2026-11-03-DAY-101/employees/2001
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse), `2001` в `employeeIds` и `extraEmployeeIds`.

| Код | Когда |
| --- | --- |
| 404 | Нет такой смены или сотрудника |
| 409 | Сотрудник отсутствует (отпуск, больничный, ...) в этот день; уже работает в этой смене; в это время работает на другой линии |

### DELETE `/crew-schedules/crew-shifts/{id}/employees/{employeeId}` — сотрудник не работает в смене

```http
DELETE /crew-schedules/crew-shifts/2026-11-03-DAY-101/employees/1001
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse), `1001` в `absentEmployeeIds` (член бригады) или убран из
`extraEmployeeIds` (замена). 409 — сотрудник не работает в этой смене.

### POST `/crew-schedules/crew-shifts/{id}/replacements` — заменить сотрудника

Одним шагом: `employeeId` не работает в этой смене, `substituteId` работает вместо него.

```http
POST /crew-schedules/crew-shifts/2026-11-03-DAY-101/replacements
X-Session-Id: a1b2c3
Content-Type: application/json

{ "employeeId": 1001, "substituteId": 2001 }
```

Ответ: [`RotationResponse`](#ответ-rotationresponse).

| Код | Когда |
| --- | --- |
| 400 | Нет `employeeId` или `substituteId`; сотрудник заменяет сам себя |
| 404 | Нет такой смены или сотрудника |
| 409 | `employeeId` не работает в смене; `substituteId` отсутствует, уже работает в смене или в это время на другой линии |

### PUT `/crew-schedules/crew-shifts/{id}/composition` — число людей по ролям для смены

Только для этой смены, вместо состава бригады (PLR_RLBR). Роли с `count: 0` не нужны в смене; одинаковые `roleId`
складываются. Позиции смены создаются заново, оставшиеся позиции сохраняют сотрудника. Если числа совпадают
с составом бригады, `roleCounts` становится `null`.

```http
PUT /crew-schedules/crew-shifts/2026-11-03-DAY-101/composition
X-Session-Id: a1b2c3
Content-Type: application/json

{
  "roles": [
    { "roleId": 5, "count": 3 },
    { "roleId": 7, "count": 1 }
  ]
}
```

Ответ: [`RotationResponse`](#ответ-rotationresponse), у смены `"roleCounts": { "5": 3, "7": 1 }` и
`"staffCount": 4`.

| Код | Когда |
| --- | --- |
| 400 | Нет `roleId` или `count`; `count` меньше 0 или больше 50 |
| 404 | Нет такой смены или роли |

Чтобы вернуть состав бригады, используйте DELETE (ниже).

### DELETE `/crew-schedules/crew-shifts/{id}/composition` — вернуть состав бригады

```http
DELETE /crew-schedules/crew-shifts/2026-11-03-DAY-101/composition
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse), у смены `"roleCounts": null`.

## Сотрудники

### PUT `/crew-schedules/employees/{id}/crew` — перевести в другую бригаду

С даты `from` (в месяце версии) сотрудник в бригаде `crewId`; меняется состав обеих бригад во всех их сменах
с этой даты. Более поздние переводы сотрудника удаляются. Без `from` (или 1-го числа) — на весь месяц:
меняется `crew`, а не `crewChanges`.

```http
PUT /crew-schedules/employees/1001/crew
X-Session-Id: a1b2c3
Content-Type: application/json

{ "crewId": 2, "from": "2026-11-15" }
```

Ответ: [`CrewDetails[]`](#ответ-crewdetails), у сотрудника
`"crewChanges": [{ "from": "2026-11-15", "crew": { "id": 2, ... } }]`.

| Код | Когда |
| --- | --- |
| 400 | Нет `crewId`; дата не в месяце версии |
| 404 | Нет такого сотрудника или бригады |

### DELETE `/crew-schedules/employees/{id}/crew-changes/{from}` — отменить перевод

```http
DELETE /crew-schedules/employees/1001/crew-changes/2026-11-15
X-Session-Id: a1b2c3
```

Ответ: [`CrewDetails[]`](#ответ-crewdetails). 404 — у сотрудника нет перевода с этой даты
(`Иванова Анна Петровна has no crew change on 2026-11-15.`).

### POST `/crew-schedules/employees/{id}/absences` — добавить отсутствие

`type`: `VACATION` (отпуск), `SICK_LEAVE` (больничный), `DISMISSAL` (увольнение, без `to`), `OTHER`.
`from` и `to` включительно. Сотрудник не работает ни в одной смене, которая начинается в эти дни
(ночная смена — по дате начала). Id отсутствия создаёт сервер.

```http
POST /crew-schedules/employees/1001/absences
X-Session-Id: a1b2c3
Content-Type: application/json

{ "type": "VACATION", "from": "2026-11-20", "to": "2026-11-30" }
```

Увольнение:

```json
{ "type": "DISMISSAL", "from": "2026-11-25" }
```

Ответ: [`RotationResponse`](#ответ-rotationresponse), сотрудник в `unavailableEmployeeIds` смен в эти дни.
Новое отсутствие с его `id` видно в `GET /crew-schedules/crews` или `GET /crew-schedules`.

| Код | Когда |
| --- | --- |
| 400 | Нет `type` или `from`; нет `to` (кроме `DISMISSAL`); `from` позже `to`; период не пересекается с месяцем версии |
| 404 | Нет такого сотрудника |
| 409 | Пересекается с другим отсутствием сотрудника |

### DELETE `/crew-schedules/employees/{id}/absences/{absenceId}` — удалить отсутствие

```http
DELETE /crew-schedules/employees/1001/absences/0b6e2f9a-8c1d-4e57-a3f0-5d2c9b7e1a44
X-Session-Id: a1b2c3
```

Ответ: [`RotationResponse`](#ответ-rotationresponse). 404 — нет такого сотрудника или отсутствия.
