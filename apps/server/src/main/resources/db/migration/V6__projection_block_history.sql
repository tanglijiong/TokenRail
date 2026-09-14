create table projection_block (
    deployment_id varchar(66) not null,
    projection_version integer not null,
    block_number bigint not null,
    block_hash varchar(66) not null,
    primary key(deployment_id, projection_version, block_number),
    foreign key(deployment_id, projection_version) references indexer_checkpoint on delete cascade
);
insert into projection_block select distinct deployment_id, projection_version, block_number, block_hash from position_snapshot;
insert into projection_block select deployment_id, projection_version, block_number, block_hash from indexer_checkpoint on conflict do nothing;
