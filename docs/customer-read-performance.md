# Customer read performance

The mobile history screen uses `GET /api/v1/orders/history?page=0&size=20`.
Optional `status=COMPLETED` or `status=CANCELLED` filters run before pagination.
The endpoint requires the CUSTOMER role and always scopes rows to the authenticated user.
Responses contain `{items, page, size, hasNext}`. Size is limited to 50.

Each page performs one bounded SQL query, retrieving one extra row to determine `hasNext`.
Cards contain the order ID/code/status, service ID/name, address, creation time, mechanic name
and total amount. Photos, contact details, quote lines and status history are loaded only when
opening `GET /orders/{id}`. The amount follows the existing mobile display rule: latest quote
when approved, otherwise latest payment, otherwise call-out fee.

`GET /api/v1/orders/active` returns the latest nonterminal order owned by the customer, using
the existing detail visibility rules, or HTTP 204. Session restoration no longer loads every
historical order just to find the active one. The existing `GET /orders` contract is preserved.

Restart/redeploy the backend before running the updated mobile app. Flyway applies V8 indexes
at startup. Existing clients using the original endpoints continue to work.

The mobile mechanic acceptance path waits at most 1500ms for location, reusing a fix at most
15 seconds old with accuracy within 100m. If unavailable, acceptance uses the server's saved
partner position, as before. In development, `[accept-order]` logs `gpsMs`, `acceptApiMs`,
`detailApiMs`, and `totalMs`, without coordinates or credentials. These measurements help
separate GPS delay, server acceptance delay and detail loading. Customer state still updates
through its existing 4-second polling interval; these changes do not provide push events.

Checks:

```sh
mvn -Dtest=CustomerOrderReadControllerTest,CustomerOrderReadRepositoryTest,CustomerOrderReadsContainerTest test
```

The container tests use an isolated PostGIS database and skip when Docker is unavailable.
Measure end-to-end timing on both emulators after restarting the backend; query reduction
alone is not a measured latency guarantee.
