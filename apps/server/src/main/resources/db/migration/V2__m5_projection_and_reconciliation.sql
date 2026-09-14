create table asset (
    deployment_id varchar(66) not null references deployment(deployment_id) on delete cascade,
    contract_address varchar(42) not null,
    symbol varchar(16) not null,
    decimals integer not null check (decimals between 0 and 77),
    deployment_block bigint not null check (deployment_block >= 0),
    primary key (deployment_id, contract_address),
    unique (deployment_id, symbol)
);

create table indexer_checkpoint (
    deployment_id varchar(66) not null references deployment(deployment_id) on delete cascade,
    projection_version integer not null check (projection_version > 0),
    block_number bigint not null check (block_number >= 0),
    block_hash varchar(66) not null,
    status varchar(20) not null check (status in ('ACTIVE', 'DIVERGED', 'SUPERSEDED')),
    updated_at timestamptz not null default now(),
    primary key (deployment_id, projection_version)
);
create unique index one_active_projection_per_deployment
    on indexer_checkpoint(deployment_id) where status = 'ACTIVE';

create table chain_event (
    event_id uuid primary key,
    deployment_id varchar(66) not null references deployment(deployment_id) on delete cascade,
    projection_version integer not null,
    block_number bigint not null,
    block_hash varchar(66) not null,
    transaction_hash varchar(66) not null,
    transaction_index bigint not null,
    log_index bigint not null,
    contract_address varchar(42) not null,
    topic0 varchar(66) not null,
    topics jsonb not null,
    data text not null,
    created_at timestamptz not null default now(),
    unique (deployment_id, projection_version, block_hash, transaction_hash, log_index),
    foreign key (deployment_id, contract_address)
        references asset(deployment_id, contract_address)
);

create table position_entry (
    event_id uuid not null references chain_event(event_id) on delete cascade,
    wallet_address varchar(42) not null,
    asset_address varchar(42) not null,
    delta numeric(78,0) not null,
    primary key (event_id, wallet_address)
);

create table offchain_position (
    deployment_id varchar(66) not null,
    projection_version integer not null,
    wallet_address varchar(42) not null,
    asset_address varchar(42) not null,
    balance numeric(78,0) not null check (balance >= 0),
    updated_block bigint not null,
    primary key (deployment_id, projection_version, wallet_address, asset_address),
    foreign key (deployment_id, asset_address)
        references asset(deployment_id, contract_address) on delete cascade
);

create table position_snapshot (
    deployment_id varchar(66) not null,
    projection_version integer not null,
    block_number bigint not null,
    block_hash varchar(66) not null,
    wallet_address varchar(42) not null,
    asset_address varchar(42) not null,
    balance numeric(78,0) not null check (balance >= 0),
    primary key (deployment_id, projection_version, block_number, wallet_address, asset_address),
    foreign key (deployment_id, asset_address)
        references asset(deployment_id, contract_address) on delete cascade
);

create table reconciliation_run (
    reconciliation_id uuid primary key,
    deployment_id varchar(66) not null references deployment(deployment_id) on delete cascade,
    projection_version integer not null,
    block_number bigint not null,
    block_hash varchar(66) not null,
    head_block bigint,
    status varchar(32) not null check (status in (
        'MATCH', 'LAG', 'POSITION_MISMATCH', 'BACKING_MISMATCH',
        'SNAPSHOT_UNAVAILABLE', 'CHAIN_DIVERGENCE')),
    lag_blocks bigint not null default 0,
    detail text,
    created_at timestamptz not null default now()
);

create table reconciliation_break (
    break_id uuid primary key,
    reconciliation_id uuid not null references reconciliation_run(reconciliation_id) on delete cascade,
    break_type varchar(32) not null,
    wallet_address varchar(42),
    asset_address varchar(42),
    onchain_amount numeric(78,0),
    offchain_amount numeric(78,0),
    delta numeric(78,0),
    resolution_status varchar(16) not null default 'OPEN'
        check (resolution_status in ('OPEN', 'RESOLVED')),
    resolved_by_reconciliation_id uuid references reconciliation_run(reconciliation_id),
    created_at timestamptz not null default now()
);

create index chain_event_order_idx on chain_event(
    deployment_id, projection_version, block_number, transaction_index, log_index);
create index position_snapshot_lookup_idx on position_snapshot(
    deployment_id, projection_version, block_number);
