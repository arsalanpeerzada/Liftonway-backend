-- Rides move through SCHEDULED -> COMPLETED or CANCELLED, replacing the cancelled flag.

alter table rides add column status text not null default 'SCHEDULED'
    check (status in ('SCHEDULED', 'COMPLETED', 'CANCELLED'));
alter table rides add column ended_at timestamptz;
update rides set status = 'CANCELLED', ended_at = created_at where cancelled;

drop index rides_departure;
alter table rides drop column cancelled;
create index rides_departure on rides (departure) where status = 'SCHEDULED';

create index bookings_ride on bookings (ride_id, status);

-- In-app notifications. Names are not copied in: the actor is joined at read time, so a rename or
-- account deletion is reflected everywhere at once.
create table notifications (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    kind        text        not null check (kind in (
                    'RIDE_REQUEST', 'REQUEST_ACCEPTED', 'REQUEST_DECLINED', 'REQUEST_WITHDRAWN',
                    'RIDE_CANCELLED', 'RIDE_COMPLETED')),
    actor_id    uuid        references users (id) on delete set null,
    ride_id     uuid        references rides (id) on delete cascade,
    booking_id  uuid        references bookings (id) on delete cascade,
    created_at  timestamptz not null,
    read_at     timestamptz
);
create index notifications_user on notifications (user_id, created_at desc);
