alter table artifacts
    add column failure_category text null,
    add column failure_reason text null;

alter table artifacts
    add constraint ck_artifacts_failure_category check (
        failure_category is null or length(failure_category) > 0
    ),
    add constraint ck_artifacts_failure_reason_paired check (
        (failure_category is null and failure_reason is null)
        or (failure_category is not null and failure_reason is not null)
    );
