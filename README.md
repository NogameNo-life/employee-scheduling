# Employee scheduling

Plans which employees work on which shift (DAY 08:00-20:00, NIGHT 20:00-08:00) on the packaging lines.
Crews rotate "Day -> Night -> Sleep day -> Day off". Java 21, Spring Boot 3.5, MS SQL Server (MES and TABU2).

## Configuration

Copy `env.template` to `.env` and fill in the values.

## Run locally

```shell
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Open http://localhost:8080 (Swagger UI: http://localhost:8080/swagger-ui.html).
The `local` profile logs SQL. `spring-boot:run` adds the JVM option that TABU2 needs (TLS 1.0, see
`src/main/jvm/legacy-tls.security`). When you start the jar yourself, add it too:

```shell
mvn package
java -Djava.security.properties=src/main/jvm/legacy-tls.security -jar target/employee-scheduling.jar
```

## Run with Docker

```shell
docker compose up -d --build
```

The image is built from the sources (`Dockerfile`), the settings come from `.env`.

## REST API

All endpoints work on the caller's schedule version (header `X-Session-Id`, `default_session_id` when missing).

| Method | Path                                    | Meaning                                              |
|--------|-----------------------------------------|------------------------------------------------------|
| GET    | `/crew-schedules`                       | The whole version: crew shifts and positions         |
| GET    | `/crew-schedules/rotation`              | Day crew and night crew for every line and day       |
| POST   | `/crew-schedules/init?date=yyyy-MM-dd`  | Reload data from the DB and generate a new version   |
