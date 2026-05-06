# Aegis Alpha API

Spring Boot 2.7 + MyBatis + MySQL + Redis API for the Aegis Alpha Platform.

## Runtime

This machine currently has Java 8, so the project uses Spring Boot 2.7.x instead of Spring Boot 3.x.

## MySQL Setup

Run once with an admin MySQL account:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p < .\src\main\resources\db\mysql\create-database.sql
```

Then import schema and observed website data:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -umarketmind -pmarketmind_dev aegis_alpha < .\src\main\resources\db\mysql\schema.sql
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -umarketmind -pmarketmind_dev aegis_alpha < .\src\main\resources\db\mysql\import-existing-data.sql
```

The application also contains `ExistingDataSeeder`, which imports the same observed data on startup when the DB is empty.

## Redis

Configure Redis through environment variables:

```powershell
$env:MARKETMIND_REDIS_HOST = "127.0.0.1"
$env:MARKETMIND_REDIS_PORT = "6379"
$env:MARKETMIND_REDIS_PASSWORD = "1234"
```

Dashboard cache uses Redis and falls back to DB if Redis is unavailable.

## Run

```powershell
$env:MARKETMIND_DB_USER = "marketmind"
$env:MARKETMIND_DB_PASSWORD = "marketmind_dev"
$env:MARKETMIND_DB_URL = "jdbc:mysql://127.0.0.1:3306/aegis_alpha?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
mvn spring-boot:run
```

Open:

```text
http://127.0.0.1:5178
```

Login:

```text
guanghui.nie / guanghui.nie
```

## API Contract

- `POST /_backend/auth/login`
- `GET /_backend/auth/me`
- `GET /_backend/profile`
- `GET /_backend/dashboard`
- `GET /_backend/workflows`
- `GET|POST /_backend/workflow/runs`
- `GET /_backend/agents`
- `POST /_backend/agents`
- `POST /_backend/agents/{agentId}/copy`
- `GET|POST /_backend/portfolio/portfolios`
- `GET|POST /_backend/backtest/history`
- `GET /_backend/api/chat/threads`
- `POST /_backend/chat/messages`

## Tests

```powershell
& "C:\Users\hustu\.m2\wrapper\dists\apache-maven-3.8.7-bin\678cc9d4\apache-maven-3.8.7\bin\mvn.cmd" test
```

The tests use H2 in MySQL compatibility mode and cover the frontend API contract.
