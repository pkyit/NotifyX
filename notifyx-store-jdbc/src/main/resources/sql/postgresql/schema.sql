-- PostgreSQL 12+（生产 DDL；索引单独建）
create table nx_audit (
    id          bigint       not null primary key,
    event_id    varchar(64)  not null,
    scene       varchar(64),
    level       varchar(16),
    biz_id      varchar(128),
    channel     varchar(32),
    receiver    varchar(256),
    status      varchar(16),
    error_msg   varchar(1024),
    attempts    int,
    cost_ms     bigint,
    instance_id varchar(64),
    trace_id    varchar(64),
    app_id      varchar(64),
    tenant      varchar(64),
    created_at  timestamp    not null,
    sent_at     timestamp
);
create index idx_nx_audit_scene_created on nx_audit (scene, created_at);
create index idx_nx_audit_receiver_created on nx_audit (receiver, created_at);
create index idx_nx_audit_event on nx_audit (event_id);

create table nx_failure_record (
    record_id      varchar(64)  not null primary key,
    envelope_json  text         not null,
    channel        varchar(32),
    status         varchar(16)  default 'PENDING',
    failure_reason varchar(1024),
    attempts       int          default 0,
    version        int          default 0,
    next_retry_at  timestamp,
    created_at     timestamp,
    updated_at     timestamp
);
create index idx_nx_fail_status_retry on nx_failure_record (status, next_retry_at);
