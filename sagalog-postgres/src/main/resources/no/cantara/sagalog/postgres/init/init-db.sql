CREATE SCHEMA IF NOT EXISTS sagalog AUTHORIZATION test;

CREATE TABLE IF NOT EXISTS sagalog.Locks (
    namespace       varchar NOT NULL,
    instance_id     varchar NOT NULL,
    log_id          varchar NOT NULL,
    lock_key        bigint  NOT NULL,
    PRIMARY KEY (namespace, instance_id, log_id)
);

CREATE TABLE IF NOT EXISTS sagalog.Sagalog (
    entry_id        uuid      NOT NULL,
    txid            varchar   NOT NULL,
    entry_type      smallint  NOT NULL,
    node_id         varchar   NOT NULL,
    saga_name       varchar   NULL,
    data            json      NULL,
    PRIMARY KEY (entry_id, txid)
);
