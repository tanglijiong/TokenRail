create table admin_job (
    job_id uuid primary key,
    deployment_id varchar(66) not null references deployment,
    actor_id varchar(64) not null,
    kind varchar(20) not null check(kind in ('RECONCILE','REBUILD')),
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    target_block bigint not null,
    status varchar(20) not null default 'PENDING' check(status in ('PENDING','RUNNING','COMPLETED','FAILED')),
    claim_version bigint not null default 0,
    lease_until timestamptz,
    result_id varchar(66),
    error text,
    created_at timestamptz not null default now(),
    unique(deployment_id, actor_id, kind, idempotency_key)
);
alter table indexer_checkpoint add column rebuild_key uuid unique;
