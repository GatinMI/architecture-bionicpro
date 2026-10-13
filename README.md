# BionicPRO

## Запуск

Весь стек — в `docker-compose.yaml`:

```bash
cp .env.example .env   # вписать YANDEX_CLIENT_ID / YANDEX_CLIENT_SECRET (см. ниже)
docker compose up --build
```



| Сервис | Порт | Назначение |
|---|---|---|
| frontend | 3000 | nginx + SPA; проксирует `/api/`, `/oauth2/`, `/login/`, `/logout` на BFF |
| bionicpro-auth | 8081 | BFF: интеграция с Keycloak, сессии, TokenRelay на user-api и report-api |
| keycloak | 8080 | realm `reports-realm`: пользователи, брокер Яндекса, LDAP, MFA |
| user-api | 8083 | профиль Яндекса + согласие пользователя, Postgres (r2dbc) |
| report-api | 8085 | отчёт по пользователю из витрины ClickHouse (`GET /api/reports`) |
| yandex-shim | 8090 | адаптер Яндекс ID → OIDC для Identity Brokering Keycloak |
| openldap | 389/636 | пользователи представительства в другой стране (`ldap/config.ldif`) |
| user-db / keycloak_db | 5434 / 5433 | PostgreSQL для user-api и Keycloak |
| crm-db | 5435 | эмуляция БД CRM (клиенты, протезы), источник ETL |
| telemetry-db | 5436 | телеметрия с чипов («База данных» из исходной C4), источник ETL |
| clickhouse | 8123/9000 | OLAP: снимки CRM, телеметрия, витрина отчёта, watermark ETL |
| airflow-apiserver | 8084 | Airflow 3 (scheduler/worker/dag-processor/triggerer — отдельные сервисы) |
| airflow-db | — | метаданные Airflow (PostgreSQL, наружу не торчит) |
| redis | 6379 | сессии BFF (access + refresh-токены), брокер Celery (db 1) |

Админка Keycloak: `admin / admin`. Для логина через настоящий Яндекс ID нужно
зарегистрировать OAuth-приложение на oauth.yandex.ru (тип «Веб-сервис», redirect URI
`http://localhost:8080/realms/reports-realm/broker/yandex/endpoint`, права «Логин, имя и
фамилия, пол» + «Адрес электронной почты») и положить `client_id`/`client_secret` в `.env`.


## Задание 1 — архитектура управления учётными данными

См. [task1-c4.drawio](task1-c4.drawio). Три принципа, на которые завязана вся
схема:

1. **Единая точка аутентификации — realm Keycloak.** Все три типа пользователей
   auth'ятся через один realm `reports-realm`: локальные пользователи живут прямо в
   Keycloak, сотрудники представительств в другой стране — через LDAP-федерацию
   (данные учётных записей запрашиваются из внешнего источника в стране представительства,
   локальное копирование ПДН не требуется — Keycloak ходит в LDAP по мере надобности),
   а пользователи российского IdP — через Identity Brokering. Архитектура не привязана к
   Яндексу: IdP другой страны добавляется как ещё один OIDC-брокер со своими
   identity-provider mappers, без изменения остальных сервисов.
2. **Токены IdP не попадают во фронтенд.** Браузер общается только с BFF
   (`bionicpro-auth`) и держит одну сессионную cookie. Access/refresh-токены Keycloak
   живут на серверной стороне (в сессии в Redis), а во внутренние сервисы BFF
   прокидывает их через `TokenRelay` уже сам — фронтенд к Keycloak не ходит вообще.
3. **Stateless сервисы.** BFF и user-api не хранят собственного состояния — сессии и
   токены лежат в Redis, данные — в Postgres. Поэтому решение горизонтально масштабируемо:
   ещё одну реплику `bionicpro-auth` или `user-api` можно поставить за балансировщик, и
   она сразу примет трафик. Stateful-состав (Keycloak, базы, Redis) в продакшене также
   разворачивается по стандартным схемам HA.


Новый бэкенд-сервис на Spring Boot (WebFlux + Spring Security OAuth2 Client) берёт на
себя всю работу с Keycloak:

- **Токены на сервере.** Access- и refresh-токены хранятся в сессии (Redis через
  Spring Session) 
- **Ротация сессии против session fixation.** `SessionRotationWebFilter` при каждом
  запросе к защищённому ресурсу, успешно прошедшем проверку сессии, перепривязывает
  access/refresh-токены к новому session id и обновляет cookie в ответе.
- **Токены во внутренние сервисы.** `bionicpro-auth` работает ещё и как Gateway:
  маршрут `/api/users/**` с фильтром `TokenRelay` прокидывает access-токен Keycloak в
  заголовок `Authorization` на user-api; user-api валидирует его по JWKS Keycloak.
  Фронтенд при этом ходит только в BFF по своей сессионной cookie.

Яндекс ID не является OIDC-совместимым (в ответе `/token` нет `id_token`, в `/info`
нет `sub`), поэтому Keycloak-брокер настраивается не на Яндекса напрямую, а на локальный
адаптер **`yandex-oidc-shim`**, который для Keycloak эмулирует OIDC IdP:

- `GET /authorize` — 302-редирект браузера на `oauth.yandex.ru/authorize`
  (scope `login:info login:email`);
- `POST /token` — серверный обмен code на токен Яндекса, дальше запрос профиля
  `GET /info?format=json` и выдача Keycloak собственного `id_token` (RS256,
  `iss=http://localhost:8090`, `aud=bionicpro`, `sub` = id Яндекса, плюс кастомные
  claims `yandex_account_id` и `bionicpro_role`), `/jwks` — публичный ключ;
- на стороне брокера `disableNonce=true`, т.к. Яндекс nonce не поддерживает: защита от
  replay остаётся за однократным code и проверкой state.

Identity-provider mappers брокера: четыре attribute-mappers (claims
`yandex_account_id` / `preferred_username` / `email` / `name` → атрибуты пользователя
`yandex_account_id` / `yandex_login` / `yandex_email` / `yandex_full_name`) и
role-mapper (`bionicpro_role=user` → realm-роль `user`), так что Яндекс-пользователь
после первого входа выглядит в системе как любой другой.

Профиль и согласие:

- user-api (Spring WebFlux, r2dbc → Postgres `user_db`) читает профиль из
  access-токена Keycloak (claims, проброшенные через protocol mappers клиента
  `bionicpro-auth`) и хранит его в таблице `yandex_user_profiles`;
- `GET /api/users/me/yandex` отдаёт текущее состояние (404 `not_a_yandex_user` для
  не-Яндекс-пользователей), `POST /api/users/me/yandex-consent` с `{"consent": true|false}`
  фиксирует решение (GRANTED/DENIED) — один раз, повторно сервис разрешением не
  спрашивает;
- фронтенд (ReportPage) показывает диалог согласия сразу после логина, пока решение не
  зафиксировано в БД.

Что я бы изменил для продакшена

- **Готовые решения вместо spring gateway.**  oauth2-proxy, authelia и тд.
- **У Яндекса есть Keycloak-расширение.** Альтернатива shim'у — готовый провайдер из
  [keycloak-russian-providers](https://github.com/playa-ru/keycloak-russian-providers):
  Яндекса в Keycloak добавляется как одно расширение, без отдельного микросервиса.
- **Keycloak нужен публичный адрес.** В репо Keycloak живёт на `localhost:8080`, из-за
  чего приходится изворачиваться с переопределением настроек OAuth2-клиентов: у BFF
  authorization-uri для браузера — `http://localhost:8080/...`, а token/jwks/userinfo-uri
  для серверных вызовов — уже внутренний `http://keycloak:8080/...`; в realm-export
  прописан `sslRequired: none`, у shim'а — два набора URL (браузерные `localhost:8090` и
  серверные `yandex-shim:8090`). С публичным HTTPS-адресом Keycloak issuer, redirect URIs
  и `sslRequired` становятся единственными, и все эти переопределения можно убрать.


## Задание 2 — сервис отчётов

См. [task2-reports.drawio](task2-reports.drawio). Три части: конвейер данных
(Airflow → ClickHouse), бэкенд отчётов (`report-api`) и кнопка на фронтенде.

### Источники данных

В исходной C4 телеметрия с чипов лежит в «Базе данных (PostgreSQL)», а данные о клиентах —
в CRM (Битрикс24). Оба источника эмулирую PostgreSQL-сервисами:

- `telemetry-db` — таблица `prosthetic_telemetry`: почасовые срезы по каждому протезу
  (батарея, качество миосигнала, движения, распознанные жесты, коды ошибок, температура
  чипа). `db/telemetry/001_init.sql` генерит детерминированные ~75 дней данных;
- `crm-db` — `customers` + `prostheses` (у одного клиента может быть несколько протезов),
  `db/crm/001_init.sql`. E-mail клиентов совпадают с e-mail пользователей Keycloak:
  именно по e-mail сервис отчётов связывает JWT с клиентом CRM (в продакшене вместо
  e-mail был бы стабильный внешний идентификатор клиента).

### Конвейер: Airflow → ClickHouse

`airflow/dags/bionicpro_report_etl.py` — DAG `bionicpro_report_etl`, расписание ежедневно
в 02:00, `catchup=False` (бэкилл за прошедшие периоды при первом запуске не выполняется):

1. `load_crm` — полный снимок CRM-таблиц (TRUNCATE + INSERT, таблицы маленькие);
2. `load_telemetry` — инкремент: только срезы после `max(recorded_at)`, уже имеющегося в
   ClickHouse; таблица `ReplacingMergeTree`, поэтому повторная загрузка тех же срезов
   (повторный запуск) схлопывается;
3. `build_view` — пересборка витрины `report_prosthesis_daily`: агрегаты телеметрии в
   разрезе клиент/протез/день. `ORDER BY (customer_email, prosthesis_id, day)` — срез по
   пользователю это prefix-скан, `PARTITION BY` месяц;
4. `update_watermark` — запись в `etl_watermark`: «данные обработаны до даты N».



### report-api

`report-api` Spring WebFlux 

`GET /api/reports?from=YYYY-MM-DD&to=YYYY-MM-DD` (период необязателен — по умолчанию весь
обработанный период):

### Фронтенд

ReportPage: кнопка «Download Report» + выбор периода (от/до; пусто = весь обработанный
период), отчёт рендерится на странице (сводка по протезу + подневная таблица), отдельная
кнопка сохраняет отчёт как `.json`, ошибки 400/404/503 показываются человеческим
сообщением («Данные доступны до …»).


