alter table deployment add column anchor_block bigint;
alter table deployment add column anchor_hash varchar(66);
create table deployment_participant (
    deployment_id varchar(66) not null references deployment(deployment_id),
    participant_id varchar(64) not null references participant(participant_id),
    wallet_address varchar(42) not null,
    signer_key_file text,
    token_hash varchar(64) not null,
    role varchar(20) not null default 'ACCOUNT' check (role in ('ACCOUNT', 'RECONCILER')),
    primary key (deployment_id, participant_id),
    unique (deployment_id, wallet_address),
    unique (deployment_id, token_hash)
);
create table deployment_account (
    deployment_id varchar(66) not null,
    participant_id varchar(64) not null,
    available numeric(78,0) not null check (available >= 0),
    reserved numeric(78,0) not null check (reserved >= 0),
    version bigint not null default 0,
    primary key (deployment_id, participant_id),
    foreign key (deployment_id, participant_id) references deployment_participant
);
create table deployment_backing (
    deployment_id varchar(66) primary key references deployment(deployment_id),
    amount numeric(78,0) not null check (amount >= 0)
);
-- Never guess which deployment owns legacy bank balances.
do $$ begin
    if (select count(*) from deployment) > 1 and
       (exists(select 1 from deposit_account) or exists(select 1 from deposit_backing where amount <> 0)) then
        raise exception 'Legacy bank balances require explicit deployment attribution before V4 migration';
    end if;
end $$;
insert into deployment_participant(deployment_id, participant_id, wallet_address, signer_key_file, token_hash)
select d.deployment_id, p.participant_id, p.wallet_address, p.signer_key_file, p.token_hash
from deployment d cross join participant p;
insert into deployment_account(deployment_id, participant_id, available, reserved, version)
select d.deployment_id, a.participant_id, a.available, a.reserved, a.version
from deployment d cross join deposit_account a;
insert into deployment_backing select d.deployment_id, b.amount from deployment d cross join deposit_backing b;
alter table api_request add column deployment_id varchar(66) references deployment;
update api_request r set deployment_id = o.deployment_id from deposit_operation o where r.operation_id = o.operation_id;
alter table api_request alter column deployment_id set not null;
alter table api_request drop constraint api_request_pkey;
alter table api_request add primary key(deployment_id, actor_id, route, idempotency_key);
alter table chain_transaction add column target_address varchar(42);
update chain_transaction t set target_address = d.deposit_token_address from deployment d where t.deployment_id = d.deployment_id;
alter table chain_transaction alter column target_address set not null;
