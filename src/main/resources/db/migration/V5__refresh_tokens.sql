-- Long-lived sessions without long-lived access tokens. Each sign-in starts a family; every refresh spends the
-- current token and issues the next one in the same family. Presenting a spent token again means it was
-- copied, so the whole family is revoked. Only an HMAC of each token is stored.

create table refresh_tokens (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    family_id   uuid        not null,
    token_hash  text        not null,
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    used_at     timestamptz,
    revoked_at  timestamptz
);
create index refresh_tokens_family on refresh_tokens (family_id);
create index refresh_tokens_user on refresh_tokens (user_id);
create index refresh_tokens_expires on refresh_tokens (expires_at);
