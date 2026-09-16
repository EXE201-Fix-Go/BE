# Fix&Go Backend

Java 21, Spring Boot 3.5.16, Maven, Spring Security, JPA and Flyway.
Step 1 implements accounts and role-based authorization for Customer Mobile,
Partner Mobile and Admin Web using the same REST API.

## Run locally

Requirements: JDK 21 and an Internet connection for the first Maven download.

```powershell
cd D:\IdeaProjects\BE_EXE201
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

The API listens on `http://localhost:8080/api/v1`. The root URL has no web UI.
The `local` profile creates a persistent H2 database and a random signing key
under `.local/` (excluded from Git). No administrator or default password is created.
Restarting with the same database/key preserves accounts and unexpired sessions.
Keep `.local/` private and use this profile only for development.

In IntelliJ, select **File > Open**, choose this project's `pom.xml` and select
**Open as Project**. Wait for Maven synchronization and indexing to finish.
Alternatively, use **Link Maven Projects** in the Maven tool window and select
`pom.xml`. This replaces the original plain-Java source layout with Maven's
`src/main/java` and `src/test/java` roots and loads the declared libraries.
Use JDK 21 for the project and Maven runner. Run
`com.fixgo.FixGoApplication` with `--spring.profiles.active=local` as a program argument.

If port 8080 is occupied, set `$env:PORT = "8081"` before starting.
An Android emulator reaches the host API at `http://10.0.2.2:8080/api/v1`.
A physical phone uses the computer's LAN address. Configure the mobile development
build for local HTTP as needed; deployed connections must use HTTPS.

## Provision the first administrator

Set these process environment variables before starting the application. Enter a
new email that has not already registered as a customer. Passwords require at least
8 characters and no more than 72 UTF-8 bytes.

```powershell
$env:BOOTSTRAP_ADMIN_EMAIL = Read-Host 'New administrator email'
$fixgoAdminPassword = Read-Host 'Administrator password' -AsSecureString
$env:BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $fixgoAdminPassword).Password
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Use `/auth/login` to sign in with those credentials. After first provisioning,
clear the bootstrap variables in the shell and restart normally:

```powershell
$env:BOOTSTRAP_ADMIN_EMAIL = $null
$env:BOOTSTRAP_ADMIN_PASSWORD = $null
$fixgoAdminPassword = $null
```

Bootstrap never promotes an existing customer or overwrites an existing admin's
password. An admin grants `PARTNER` to a registered customer through the admin
role endpoint. Admin accounts are managed through server configuration, not public
registration or the role endpoint.

## PostgreSQL

The default profile uses PostgreSQL. Create a dedicated database and database user,
then configure the process environment (or IntelliJ run configuration):

| Variable | Meaning |
| --- | --- |
| `DB_URL` | JDBC URL, default `jdbc:postgresql://localhost:5432/fixgo` |
| `DB_USERNAME` | Database user, default `fixgo` |
| `DB_PASSWORD` | Database password, required for your server |
| `JWT_SECRET` | Base64-encoded random bytes, minimum 32 decoded bytes |
| `PORT` | Optional HTTP port, default `8080` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated browser origins; defaults to localhost ports 3000 and 5173 |

Example signing-key generation in PowerShell 7 (the key is not printed):

```powershell
$fixgoJwtBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$env:JWT_SECRET = [Convert]::ToBase64String($fixgoJwtBytes)
.\mvnw.cmd spring-boot:run
```

Store the signing key in the deployment's secret configuration so it persists
across restarts. Flyway applies `db/migration/V1__accounts_and_sessions.sql`.
Hibernate validates the schema; it does not create or silently alter tables.
`.env` files are not automatically loaded by this application.

## Supabase

Use the `supabase` profile for the private `fixgo` schema:

```text
--spring.profiles.active=supabase
```

Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `JWT_SECRET` in your local run
configuration. For IPv4 development, copy the Session pooler connection details
from Supabase Connect and use a JDBC URL with SSL. Do not use API keys as the DB
password or commit secrets. Do not combine this profile with `local`.

See [the Vietnamese setup guide](docs/supabase-setup.md) for IntelliJ steps,
signing-key generation, schema checks and troubleshooting. If the 21 ERD tables
were created manually, review the one-time Flyway baseline instructions before
starting. Automatic baselining is disabled by default. On an empty database,
Flyway creates only the three account tables currently covered by V1.

## API

See `docs/auth-api.md` for requests, responses, errors and mobile token handling.
`docs/auth.http` contains requests runnable from the IntelliJ HTTP Client.

| Method | Path (prefix `/api/v1`) | Permission |
| --- | --- | --- |
| POST | `/auth/register` | Public, always creates CUSTOMER |
| POST | `/auth/login` | Public |
| POST | `/auth/refresh` | Refresh token in JSON body |
| POST | `/auth/logout` | Current authenticated session |
| POST | `/auth/logout-all` | Authenticated user, all sessions |
| GET | `/users/me` | Authenticated user |
| PATCH | `/users/me` | Authenticated user |
| POST | `/users/me/password` | Authenticated user + current password |
| GET | `/partner/account` | PARTNER |
| GET | `/admin/users` | ADMIN |
| GET | `/admin/users/{id}` | ADMIN |
| PATCH | `/admin/users/{id}/role` | ADMIN, CUSTOMER/PARTNER only |
| PATCH | `/admin/users/{id}/status` | ADMIN, ACTIVE/DISABLED |

### Session behavior

- Passwords are hashed with BCrypt (cost 12). Plaintext passwords are never stored.
- Access JWTs last at most 15 minutes. Each login creates a separate session.
- Sessions have an absolute 7-day expiry. Refreshing does not extend that deadline.
- Refresh tokens are random and stored only as SHA-256 hashes. Each can be used once.
- Reusing an already consumed refresh token revokes its entire session. The mobile
  app must serialize refresh attempts and atomically replace both tokens.
- Logout revokes the current session, including access tokens. Logout-all, password
  changes, role changes and disabling an account revoke all that user's sessions.
- Requests validate the session and current database role, not just the JWT signature.
- Five failed password attempts temporarily block password authentication for 15
  minutes. Successful login resets the failure count. This is an account-level
  control; shared gateway/IP rate limiting remains a deployment concern.
- Self-service profile updates cannot change email, role, status or another user's data.
- CSRF is disabled for these stateless bearer-header APIs. They do not accept auth
  cookies. Revisit CSRF if a browser cookie authentication flow is introduced.

### Scope

This milestone supports email/password login. Phone numbers are optional profile
data and are not verified identifiers. Email/SMS verification, OTP login,
forgot-password delivery, Google/Apple login and partner KYC are not implemented.
PARTNER authority alone is not proof of KYC approval; the partner onboarding module
must add that separate check before allowing rescue orders.

## Tests and packaging

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

`AuthApiTest` runs API integration tests with H2 and the real Spring Security
filter chain. `PostgresAuthApiTest` runs the same contract in an isolated PostgreSQL
17 Testcontainers database when Docker is available. It is explicitly skipped if
Docker is unavailable, and does not use the developer's PostgreSQL database.

The suite checks password hashing, duplicate email, input validation, privilege
escalation, JWT validity, cross-account isolation, refresh rotation/reuse/concurrency,
logout, password changes, account status, role changes, pagination and CORS.

The executable artifact is `target/fixgo-backend-0.0.1-SNAPSHOT.jar`:

```powershell
java -jar target/fixgo-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

## Code layout

```text
src/main/java/com/fixgo/
  FixGoApplication.java
  auth/             Registration, login, JWT, refresh and sessions
  auth/security/    Security rules, JWT validation and CORS
  user/             Account entity and self-service profile
  admin/            Account administration and bootstrap
  partner/          Partner account endpoint
  common/           Validation and API errors
src/main/resources/db/migration/
src/test/java/com/fixgo/auth/
```

Framework references: [Spring Security JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
and [password storage](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html).
