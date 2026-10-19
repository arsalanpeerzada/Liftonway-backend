# Lift On Way backend

The API behind the Lift On Way app: phone sign-in with one-time codes, profiles, vehicles, places, publishing and searching rides, and seat requests with the 4-hour cancellation rule.

Built with Spring Boot 4.1 and Kotlin, on PostgreSQL with Flyway migrations. The Android app lives in a separate project (`../RideSharingApp/android`).

## Run it locally

You need JDK 17 or later (Android Studio's bundled JDK works). Nothing else: no database to install and no Docker.

```bash
./gradlew bootRun
```

This turns on the `dev` profile:

- **Database:** a real PostgreSQL 17 starts inside the app (the embedded-postgres library), keeping its data in `~/.liftonway/dev-postgres` between runs. Delete that folder to start fresh.
- **Codes:** every one-time code is `1234`, and no SMS is sent.
- **Demo data:** a week of sample rides between the seeded places, with made-up drivers.

The API is then at `http://localhost:8080/api/v1`, with interactive docs at `http://localhost:8080/docs` (dev only; they are switched off in other profiles). The Android emulator reaches it at `http://10.0.2.2:8080/api/v1/`, which is the app's default.

## Tests

```bash
./gradlew test
```

- **Integration:** each run starts its own throwaway PostgreSQL, so no setup is needed. `ApiFlowTest` drives the whole journey over HTTP: publish, search including stops, request, accept, cancel either side of the 4-hour line, code rate limits and attempt caps.
- **Unit:** `RulesTest` covers the cancellation rule and phone number handling.

## API at a glance

| Method and path | What it does |
| --- | --- |
| `POST /auth/otp` | Send a code to a number |
| `POST /auth/verify` | Check the code; signs in, or signs up when a name is sent with a new number |
| `GET, PATCH /me` | The signed-in account |
| `POST /me/phone/otp`, `POST /me/phone/verify` | Change number |
| `GET, PUT /me/vehicle` | The user's car (needed to publish) |
| `GET /places?q=` | Place search (no sign-in needed) |
| `GET /rides?from&to&date&seats` | Search, matching stops in route order |
| `GET /rides/recommended` | The same route on other days |
| `POST /rides`, `GET /rides/{id}`, `GET /me/rides` | Publish, view, a driver's own rides |
| `POST /rides/{id}/bookings`, `GET /me/bookings` | Request a seat, see your requests |
| `POST /bookings/{id}/cancel`, `/accept`, `/decline` | Passenger cancels; driver answers |

Errors use RFC 9457 problem responses with a stable `code`, such as `CANCEL_WINDOW_CLOSED`, `NO_SEATS` or `TOO_MANY_REQUESTS`, and a message the app can show as-is.

## Security notes

- **Codes:**
  - Stored only as an HMAC.
  - Expire after 5 minutes.
  - Allow 5 wrong attempts each.
  - Limited to one request per 30 seconds and five per hour for each number.
  - Never logged; logs show masked numbers only.
- **Tokens:** HS256 JWTs, valid for 7 days. Outside `dev` and `test`, `LIFTONWAY_JWT_SECRET` must be set to at least 32 random bytes from a secret store, or the app refuses to start.
- **SMS:** no provider is plugged in yet. Outside `dev` and `test` the app refuses to start until an `SmsSender` bean is added, so it can never silently skip sending codes.
- **Database:** deployed builds use a managed PostgreSQL, set through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from the environment. The embedded database is development-only and is left out of the built jar.

Phone numbers and names are personal data. Before any real users, this needs a privacy notice, a retention policy for old code records, and a review against the applicable data protection law.

## Not built yet

- An SMS provider, and refresh tokens.
- Photo upload for profiles and vehicles. The app keeps photos on the device for now.
- Notifications, payments and the wallet. The app still uses in-memory data for these.
- Ratings. Drivers created through the API show as "New".
- The verify step reveals whether a number has an account (it answers `NAME_REQUIRED`). That's acceptable for a prototype, but worth revisiting.
