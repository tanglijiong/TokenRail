alter table deposit_operation drop constraint deposit_operation_kind_check;
alter table deposit_operation add check(kind in ('CONVERSION','REDEMPTION','BUSINESS'));
create table deployment_contract (
    deployment_id varchar(66) not null references deployment,
    address varchar(42) not null,
    contract_kind varchar(24) not null,
    primary key(deployment_id, address),
    unique(deployment_id, contract_kind)
);
insert into deployment_contract select deployment_id, deposit_token_address, 'CASH' from deployment;
alter table chain_transaction drop constraint transaction_deployment_target;
alter table chain_transaction add foreign key(deployment_id, target_address) references deployment_contract(deployment_id, address);
create table participant_role (
    deployment_id varchar(66) not null,
    participant_id varchar(64) not null,
    role varchar(24) not null check(role in ('ISSUER','OPERATOR','COMPLIANCE','ATTESTOR','RECONCILER')),
    primary key(deployment_id, participant_id, role),
    foreign key(deployment_id, participant_id) references deployment_participant
);
create table business_resource (
    deployment_id varchar(66) not null references deployment,
    resource_id varchar(66) not null,
    resource_kind varchar(20) not null check(resource_kind in ('SETTLEMENT','PAYMENT')),
    creator_id varchar(64) not null,
    terms jsonb not null,
    created_at timestamptz not null default now(),
    primary key(deployment_id, resource_kind, resource_id)
);
create table business_command (
    operation_id uuid primary key references deposit_operation,
    deployment_id varchar(66) not null,
    action varchar(32) not null,
    target_address varchar(42) not null,
    call_data text not null,
    resource_id varchar(66) not null,
    resource_kind varchar(20),
    pending boolean not null default true,
    foreign key(deployment_id, target_address) references deployment_contract(deployment_id, address)
);
create unique index one_pending_business_action on business_command(deployment_id, resource_id, action) where pending;
alter table chain_transaction add column mined_block bigint;
alter table chain_transaction add column mined_hash varchar(66);
alter table chain_transaction add column revert_data text;
