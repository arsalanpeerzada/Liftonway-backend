-- After a completed ride, the passenger rates the driver and the driver rates each passenger, once each.

create table ratings (
    booking_id  uuid        not null references bookings (id) on delete cascade,
    rater_id    uuid        not null references users (id) on delete cascade,
    ratee_id    uuid        not null references users (id) on delete cascade,
    stars       smallint    not null check (stars between 1 and 5),
    created_at  timestamptz not null,
    primary key (booking_id, rater_id)
);
create index ratings_ratee on ratings (ratee_id);

-- users.rating becomes the average of ratings received, kept alongside how many there are.
alter table users add column rating_count int not null default 0;
