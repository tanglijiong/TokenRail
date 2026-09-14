alter table indexer_checkpoint drop constraint indexer_checkpoint_status_check;
alter table indexer_checkpoint add check(status in ('ACTIVE','BUILDING','DIVERGED','SUPERSEDED'));
alter table indexer_checkpoint add column target_block bigint;
create unique index one_building_projection_per_deployment on indexer_checkpoint(deployment_id) where status = 'BUILDING';
create table reconciliation_evidence (
    evidence_id uuid primary key,
    reconciliation_id uuid not null references reconciliation_run on delete cascade,
    check_type varchar(32) not null,
    wallet_address varchar(42),
    asset_address varchar(42) not null,
    onchain_amount numeric(78,0) not null,
    offchain_amount numeric(78,0) not null
);
alter table reconciliation_run add column booked_backing numeric(78,0);
alter table reconciliation_run add column future_postings numeric(78,0);
alter table reconciliation_run add column pending_mints numeric(78,0);
alter table reconciliation_run add column pending_burns numeric(78,0);
