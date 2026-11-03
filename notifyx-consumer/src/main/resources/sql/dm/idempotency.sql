-- 达梦 DM8：consumer 幂等表（设计书 §11.4 / §12.2）
-- 方言要点：timestamp 需显式精度 timestamp(0)；索引单独建
create table nx_idempotency (
    idempotency_key varchar(128) not null primary key,
    event_id        varchar(64)  not null,
    created_at      timestamp(0) not null
);
create index idx_nx_idem_created on nx_idempotency (created_at);