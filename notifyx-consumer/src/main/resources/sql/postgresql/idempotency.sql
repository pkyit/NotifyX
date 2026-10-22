-- PostgreSQL 12+：consumer 幂等表（设计书 §12.2）
create table nx_idempotency (
    idempotency_key varchar(128) not null primary key,
    event_id        varchar(64)  not null,
    created_at      timestamp    not null
);
create index idx_nx_idem_created on nx_idempotency (created_at);
