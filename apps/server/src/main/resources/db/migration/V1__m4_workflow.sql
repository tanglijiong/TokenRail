create table deployment (
    deployment_id varchar(66) primary key,
    chain_id bigint not null,
    genesis_hash varchar(66) not null,
    deposit_token_address varchar(42) not null,
    active boolean not null default true,
    created_at timestamptz not null default now()
);

create table participant (
    participant_id varchar(64) primary key,
    wallet_address varchar(42) not null unique,
    token_hash varchar(64) not null unique,
    signer_key_file text,
    created_at timestamptz not null default now()
);

create table deposit_account (
    participant_id varchar(64) primary key references participant(participant_id),
    available numeric(78,0) not null check (available >= 0),
    reserved numeric(78,0) not null check (reserved >= 0),
    version bigint not null default 0
);

create table deposit_backing (
    singleton boolean primary key default true check (singleton),
    amount numeric(78,0) not null check (amount >= 0)
);
insert into deposit_backing(singleton, amount) values (true, 0);

create table deposit_operation (
    operation_id uuid primary key,
    deployment_id varchar(66) not null references deployment(deployment_id),
    participant_id varchar(64) not null references participant(participant_id),
    business_id varchar(66) not null,
    kind varchar(16) not null check (kind in ('CONVERSION', 'REDEMPTION')),
    amount numeric(78,0) not null check (amount > 0),
    status varchar(24) not null,
    tx_hash varchar(66),
    error_code varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (deployment_id, kind, business_id)
);

create table api_request (
    actor_id varchar(64) not null,
    route varchar(128) not null,
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    operation_id uuid not null references deposit_operation(operation_id),
    created_at timestamptz not null default now(),
    primary key (actor_id, route, idempotency_key)
);

create table outbox_task (
    operation_id uuid primary key references deposit_operation(operation_id),
    status varchar(16) not null check (status in ('PENDING', 'PROCESSING', 'WAITING', 'DONE')),
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table signer_nonce (
    deployment_id varchar(66) not null references deployment(deployment_id),
    signer_address varchar(42) not null,
    next_nonce numeric(78,0) not null check (next_nonce >= 0),
    primary key (deployment_id, signer_address)
);

create table chain_transaction (
    transaction_id uuid primary key,
    operation_id uuid not null unique references deposit_operation(operation_id),
    deployment_id varchar(66) not null references deployment(deployment_id),
    method varchar(64) not null,
    signer_address varchar(42) not null,
    nonce numeric(78,0) not null,
    raw_transaction text,
    tx_hash varchar(66),
    status varchar(24) not null,
    receipt_status integer,
    error_code varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (deployment_id, signer_address, nonce)
);

create table deposit_journal (
    journal_id uuid primary key,
    operation_id uuid not null references deposit_operation(operation_id),
    posting_kind varchar(32) not null,
    amount numeric(78,0) not null check (amount > 0),
    created_at timestamptz not null default now(),
    unique (operation_id, posting_kind)
);

create index outbox_available_idx on outbox_task(status, available_at);
