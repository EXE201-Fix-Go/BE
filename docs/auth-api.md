# Account API v1

Base URL: `http://localhost:8080/api/v1`. Send JSON with
`Content-Type: application/json`. UUIDs identify users. Timestamps are UTC ISO-8601.
Fields not defined in the request DTO are rejected with 400, including role/status
fields in registration or profile updates.

## Register and login

`POST /auth/register` returns 201:

```json
{
  "email": "student@example.com",
  "password": "Example-password-2026!",
  "fullName": "Nguyen Van An",
  "phoneNumber": "+84901234567"
}
```

Email is trimmed and lowercased. It must be a valid email up to 254 characters.
Full name is required and limited to 100 characters after trimming. Passwords
require at least 8 Unicode code points and at most 72 UTF-8 bytes (BCrypt limit).
Phone number is optional; omit it or use null. If supplied, use Vietnamese mobile
format such as `0901234567`, or international format such as `+84901234567`.
Registration always creates an ACTIVE CUSTOMER and signs the user in.

`POST /auth/login` returns 200:

```json
{"email":"student@example.com","password":"Example-password-2026!"}
```

Both endpoints return this structure (token strings and UUID below are placeholders):

```json
{
  "accessToken": "<signed-jwt>",
  "refreshToken": "<43-character-base64url-token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresAt": "2026-09-22T04:00:00Z",
  "user": {
    "id": "00000000-0000-0000-0000-000000000001",
    "email": "student@example.com",
    "fullName": "Nguyen Van An",
    "phoneNumber": "+84901234567",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-09-15T04:00:00Z"
  }
}
```

`expiresIn` is the access-token lifetime in seconds and is capped by the session's
absolute expiration. Use the returned `user.role` to select the mobile navigation;
the backend independently enforces authorization.

## Authenticated calls

Send `Authorization: Bearer <accessToken>`. Do not send tokens in URLs.

- `GET /users/me`: returns the user object above without the token envelope.
- `PATCH /users/me`: replaces the editable profile fields, returning the user object.
  Both name and desired phone value should be sent; omitting/nulling phone clears it.
- `POST /users/me/password`: checks the current password, replaces it and revokes
  all sessions, including the calling session. Returns 204; sign in again afterward.
- `POST /auth/logout`: revokes the calling session. No body. Returns 204.
- `POST /auth/logout-all`: revokes all sessions for the authenticated user. No body. Returns 204.

Profile request:

```json
{"fullName":"Nguyen Van An","phoneNumber":null}
```

Password request:

```json
{"currentPassword":"Example-password-2026!","newPassword":"Another-password-2026!"}
```

## Refresh on mobile

`POST /auth/refresh` takes only the refresh token in its JSON body, without an
Authorization header. A stale/expired bearer header would be rejected before this
endpoint can run.

```json
{"refreshToken":"<latest-refresh-token>"}
```

The response is the same token envelope as login. Save both new tokens atomically.
Keep sensitive tokens in platform-protected storage (Android Keystore/iOS Keychain
via the mobile framework's secure storage integration).

When several API calls need refresh, share a single in-flight refresh operation.
Each refresh token works exactly once. Retrying a consumed token, including after a
network timeout where the response was lost, revokes that session and requires login.
The backend does not extend the original 7-day session expiry.

On an expired access token, refresh once and retry the original request once.
If refresh fails with 401, clear the local session and return to login. On 403,
show a permission error instead of repeatedly refreshing. Only retry an operation
automatically when doing so is safe for its business behavior.

## Role-specific APIs

`GET /partner/account` returns the authenticated partner's user object. CUSTOMER
and ADMIN receive 403 on this partner-only endpoint.

Administrator endpoints:

| Method and path | Request | Response |
| --- | --- | --- |
| `GET /admin/users?page=0&size=20` | Page >= 0, size 1-100 | Page of user objects |
| `GET /admin/users/{id}` | User UUID | User object, or 404 |
| `PATCH /admin/users/{id}/role` | `{"role":"PARTNER"}` or `{"role":"CUSTOMER"}` | Updated user object |
| `PATCH /admin/users/{id}/status` | `{"status":"DISABLED"}` or `{"status":"ACTIVE"}` | Updated user object |

List envelope:

```json
{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

Sort order is newest accounts first, then UUID for stable ordering. The API never
returns password hashes, refresh-token hashes or session records. Role/status
changes revoke all affected user's sessions; an unchanged value is a no-op.
Admin accounts cannot be disabled or reassigned through these endpoints, and the
API cannot assign ADMIN. Use the documented bootstrap procedure for admin provisioning.

## Errors

Application and authentication errors have this structure:

```json
{
  "timestamp": "2026-09-15T04:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Please check the submitted fields.",
  "path": "/api/v1/auth/register",
  "fieldErrors": {"email":"must be a well-formed email address"}
}
```

Use `code` for mobile localization; do not parse English messages. Field error
messages may use the request locale. Browser CORS rejections are handled by Spring's
CORS filter before controllers and may return plain text.

| HTTP | Typical codes |
| --- | --- |
| 400 | `VALIDATION_ERROR`, `INVALID_REQUEST`, `ADMIN_ROLE_MANAGED_EXTERNALLY` |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` |
| 403 | `FORBIDDEN`, `ACCOUNT_DISABLED` |
| 404 | `USER_NOT_FOUND`, `NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `EMAIL_ALREADY_EXISTS`, `DATA_CONFLICT`, `ADMIN_ACCOUNT_PROTECTED` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 500 | `INTERNAL_ERROR` |

Invalid password, missing account, disabled account and temporary login lock return
the same login error. Five failed password attempts block password authentication
for 15 minutes. Registration conflict may return `DATA_CONFLICT` for concurrent
duplicate requests because uniqueness is also enforced by the database.
