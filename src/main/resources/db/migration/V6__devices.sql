-- Push registration tokens, one row per app install. A token moves to whoever signs in on that install.
create table device_tokens (
    token       text primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    updated_at  timestamptz not null
);
create index device_tokens_user on device_tokens (user_id);
