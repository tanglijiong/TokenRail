alter table deployment add constraint deployment_target_identity unique(deployment_id, deposit_token_address);
alter table chain_transaction add constraint transaction_deployment_target
    foreign key(deployment_id, target_address) references deployment(deployment_id, deposit_token_address);
