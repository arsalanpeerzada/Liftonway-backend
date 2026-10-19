# Lift On Way backend

The API behind the Lift On Way app: phone sign-in with one-time codes, profiles, vehicles, places, publishing and searching rides, seat requests with the 4-hour cancellation rule, the ride lifecycle, and in-app notifications.

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

- **Integration:** each run starts its own throwaway PostgreSQL, so no setup is needed. `ApiFlowTest` drives the whole journey over HTTP: publish, search including stops, request, accept, cancel either side of the 4-hour line, the driver's notifications, cancelling and completing rides (including the automatic completion), code rate limits and attempt caps.
- **Sessions and errors:** token expiry, refresh rotation and reuse detection, sign-out, and 4xx (never 500) answers to malformed requests.
- **Unit:** `RulesTest` covers the cancellation rule, phone number handling and the rate limiter. `TwilioSmsTest` checks the Twilio adapter against a local stand-in, so no real messages are sent.

## Deploy

The repository builds into a Docker image (`Dockerfile`) that runs with the `prod` profile and takes all configuration from environment variables. It runs anywhere that can run a container: a single server, Railway, Render, Fly.io, or a cloud provider's container service with its managed PostgreSQL.

For a single Linux server, `deploy/` has a ready setup: PostgreSQL, the API, and Caddy in front, which gets and renews HTTPS certificates on its own.

1. Point a domain (for example `api.yourdomain.com`) at the server.
2. Copy the repository to the server, then `cp deploy/.env.example deploy/.env` and fill in every value. Never commit `.env`.
3. `cd deploy && docker compose up -d --build`

What you need first:
- **Firebase:**
  - Download a service account key: Firebase console, then Project settings, Service accounts, Generate new private key.
  - Save it as `deploy/firebase-service-account.json` on the server only. Git ignores it, but don't paste it anywhere, and delete the downloaded copy once it's on the server.
  - The compose file mounts it as a secret.
- **Twilio:** an account, an API key (not the auth token), and a Messaging Service with a sender that can reach Pakistani numbers. Sending to Pakistan may need sender registration on Twilio's side; check their country guidelines before launch.
- **A token secret:** at least 32 random bytes, for example from `openssl rand -base64 48`.
- **Backups:** the compose setup keeps data in a Docker volume on that one server. Before real users, schedule `pg_dump` off the machine, or use a managed PostgreSQL with automatic backups instead (set `SPRING_DATASOURCE_*` and drop the `db` service).

The app needs the API's HTTPS address at build time: `./gradlew assembleRelease -Pliftonway.releaseApiUrl=https://api.yourdomain.com/api/v1/`.

In production:
- Logs are one JSON object per line.
- `/actuator/health/liveness` and `/readiness` serve health checks; Caddy keeps them, and the API docs, off the public internet.
- The app trusts `X-Forwarded-*` headers, so only ever expose it through the proxy.

## API at a glance

| Method and path | What it does |
| --- | --- |
| `POST /auth/otp` | Send a code to a number |
| `POST /auth/verify` | Check the code; signs in, or signs up when a name is sent with a new number |
| `POST /auth/refresh`, `POST /auth/logout` | Swap a refresh token for a new pair; end this device's session (and stop its pushes) |
| `PUT /me/device` | Register this phone for push notifications |
| `GET, PATCH /me` | The signed-in account |
| `POST /me/phone/otp`, `POST /me/phone/verify` | Change number |
| `GET, PUT /me/vehicle` | The user's car (needed to publish) |
| `GET /places?q=` | Place search (no sign-in needed) |
| `GET /rides?from&to&date&seats` | Search, matching stops in route order |
| `GET /rides/recommended` | The same route on other days |
| `POST /rides`, `GET /rides/{id}`, `GET /me/rides` | Publish, view, a driver's own rides |
| `POST /rides/{id}/bookings`, `GET /me/bookings` | Request a seat, see your requests |
| `POST /bookings/{id}/cancel`, `/accept`, `/decline` | Passenger cancels; driver answers |
| `POST /rides/{id}/cancel`, `/complete` | Driver calls a ride off, or marks it done once it has set off |
| `GET /me/notifications`, `GET /me/notifications/unread` | Requests, answers and ride updates, newest first |
| `POST /me/notifications/read`, `DELETE /me/notifications/{id}` | Mark all read; remove one |
| `POST /bookings/{id}/rating` | After a completed ride: the passenger rates the driver, or the driver rates the passenger |

### Ride rules

- **Requests:** a seat is held while a request is pending, so the driver never accepts more people than seats.
- **Driver cancels:** any time before departure while nobody is confirmed. With confirmed passengers, the same 4-hour rule as passengers applies.
- **Completion:** the driver taps Complete once the ride has set off. Rides they forget are completed 6 hours after departure by a background job, and requests nobody answered lapse.
- **Ratings:** one to five stars, once per side per seat, for 14 days after a completed ride. A user's rating is the average of what they've received. Seats show the car's number plate only once confirmed.
- **Notifications:** written in the same transaction as the change they describe, and deleted after 90 days. Names are looked up when read, never copied.
- **Push:**
  - Each notification also goes to the recipient's phones through Firebase Cloud Messaging, after the transaction commits and off the request thread.
  - Push is sent as a high-priority data message that expires after 12 hours.
  - Phones that FCM reports as gone are forgotten.
  - Without Firebase credentials (local development), push is skipped.

Errors use RFC 9457 problem responses with a stable `code`, such as `CANCEL_WINDOW_CLOSED`, `NO_SEATS` or `TOO_MANY_REQUESTS`, and a message the app can show as-is.

## Security notes

- **Codes:**
  - Stored only as an HMAC.
  - Expire after 5 minutes.
  - Allow 5 wrong attempts each.
  - Limited to one request per 30 seconds and five per hour for each number.
  - Never logged; logs show masked numbers only.
- **Access tokens:** HS256 JWTs that last 15 minutes. Outside `dev` and `test`, `LIFTONWAY_JWT_SECRET` must be set to at least 32 random bytes from a secret store, or the app refuses to start.
- **Refresh tokens:**
  - Last 60 days and are stored only as an HMAC.
  - Can be used once: each refresh returns the next one.
  - If a spent token comes back, that sign-in is ended on every copy.
  - Sign-out ends the device's sign-in on the server.
- **SMS:** Twilio, turned on by its environment variables. Outside `dev` and `test` the app refuses to start without an SMS sender, so it can never silently skip sending codes. Message text holds the code and is never logged.
- **Rate limits:**
  - 120 calls a minute per signed-in user, and 60 a minute per address when signed out.
  - Code requests are capped at 10 an hour per address, on top of 5 an hour per number, which blocks SMS pumping (paid texts sent to numbers an attacker controls).
  - Counters are kept per server, so with several servers the effective limit multiplies. Move them to Redis if that matters.
- **Retention:** a nightly job removes notifications after 90 days, sign-in code records after 30 days, and expired sessions after a week.
- **Database:** deployed builds use a managed PostgreSQL, set through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from the environment. The embedded database is development-only and is left out of the built jar.

Phone numbers and names are personal data. Before any real users, this needs a privacy notice and a review against the applicable data protection law by someone qualified.

## Not built yet

- Photo upload for profiles and vehicles. The app keeps photos on the device for now.
- Payments and the wallet. The app still uses in-memory data for these.
- Push notifications. The app checks for new ones every 30 seconds while it's open.
- The verify step reveals whether a number has an account (it answers `NAME_REQUIRED`). That's acceptable for a prototype, but worth revisiting.
