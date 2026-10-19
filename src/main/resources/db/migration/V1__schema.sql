-- Lift On Way core schema.

create table users (
    id          uuid primary key,
    name        text        not null,
    phone       text        not null unique,   -- E.164, e.g. +923001234567
    photo_url   text,
    rating      numeric(2, 1),                 -- null until the first rating
    created_at  timestamptz not null
);

-- One-time codes. Only an HMAC of the code is stored, never the code itself.
create table otp_challenges (
    id           uuid primary key,
    phone        text        not null,
    purpose      text        not null check (purpose in ('SIGN_IN', 'CHANGE_PHONE')),
    user_id      uuid references users (id) on delete cascade,
    code_hash    text        not null,
    attempts     int         not null default 0,
    created_at   timestamptz not null,
    expires_at   timestamptz not null,
    consumed_at  timestamptz
);
create index otp_challenges_phone_created on otp_challenges (phone, created_at desc);

create table vehicles (
    user_id          uuid primary key references users (id) on delete cascade,
    name             text    not null,
    model            text    not null,
    plate            text    not null,
    colour           text    not null,
    air_conditioning boolean not null,
    heater           boolean not null,
    photo_url        text,
    updated_at       timestamptz not null
);

create table places (
    id     text primary key,
    title  text not null,
    area   text not null
);

create table rides (
    id              uuid primary key,
    driver_id       uuid        not null references users (id),
    departure       timestamptz not null,
    seats_total     int         not null check (seats_total between 1 and 6),
    price_per_seat  int         not null check (price_per_seat between 50 and 50000),
    route           text        not null default '',
    vehicle_name    text        not null,
    cancelled       boolean     not null default false,
    created_at      timestamptz not null
);
create index rides_departure on rides (departure) where not cancelled;
create index rides_driver on rides (driver_id, departure);

-- The ride's path in order: position 0 is the pickup, the highest position is the destination, stops in between.
create table ride_points (
    ride_id   uuid not null references rides (id) on delete cascade,
    position  int  not null,
    place_id  text not null references places (id),
    primary key (ride_id, position)
);
create index ride_points_place on ride_points (place_id, ride_id);

create table bookings (
    id            uuid primary key,
    ride_id       uuid        not null references rides (id),
    passenger_id  uuid        not null references users (id),
    seats         int         not null check (seats between 1 and 6),
    status        text        not null check (status in ('PENDING', 'CONFIRMED', 'DECLINED', 'CANCELLED')),
    created_at    timestamptz not null,
    updated_at    timestamptz not null
);
-- A passenger holds at most one live request per ride.
create unique index bookings_one_live_per_ride on bookings (ride_id, passenger_id) where status in ('PENDING', 'CONFIRMED');
create index bookings_passenger on bookings (passenger_id, created_at desc);
