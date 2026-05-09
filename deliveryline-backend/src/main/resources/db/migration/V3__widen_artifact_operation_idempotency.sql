alter table artifact_operations
    add column artifact_type text;

update artifact_operations operation
set artifact_type = artifact.artifact_type
from artifacts artifact
where artifact.id = operation.artifact_id;

alter table artifact_operations
    alter column artifact_type set not null;

alter table artifact_operations
    drop constraint uq_artifact_operations_idem_key_op_type_artifact_id,
    add constraint uq_artifact_operations_idem_key_op_type_workflow_run unique (
        workflow_run_id,
        artifact_type,
        idempotency_key,
        operation_type
    );
