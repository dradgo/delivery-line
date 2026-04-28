alter table workflow_runs
    add column version bigint not null default 0;
