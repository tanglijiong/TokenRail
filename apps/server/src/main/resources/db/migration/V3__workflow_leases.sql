alter table outbox_task add column claim_version bigint not null default 0;
alter table outbox_task add column lease_until timestamptz;
-- Pre-migration PROCESSING rows also need recovery.
update outbox_task set lease_until = now() where status = 'PROCESSING';
create index outbox_lease_idx on outbox_task(lease_until) where status = 'PROCESSING';
