package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.impl.ExecutionQueryImpl;
import org.flowable.engine.impl.ProcessInstanceQueryImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ExecutionDataManager;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// Backs Flowable's Execution/ProcessInstance runtime state with our own process_execution table.
// Covers exactly the single-linear-execution case (the hello-world process: one root execution,
// no children, no concurrency, no sub-processes). Query-object/native-query variants (the
// ExecutionQuery/ProcessInstanceQuery API surface, not used by startProcessInstanceByKey) and
// process-instance locking are stubbed -- needed before this supports RuntimeService's generic
// query API or concurrent access to the same process instance.
//
// Every finder routes through cacheOrMap() rather than mapRow() directly: the engine mutates an
// execution in place (activity position, ended flag) across several lookups within one command,
// and expects the *same* Java object back each time -- see ProcessSession's cache for why.
public class ExecutionDataManagerImpl extends AbstractProcessDataManager implements ExecutionDataManager {

    private static final String COL_REVISION = "revision";
    private static final String PARAM_PARENT_ID = "parentId";

    @Override
    public ExecutionEntity create() {
        return new ExecutionEntityImpl();
    }

    @Override
    public ExecutionEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(ExecutionEntity entity) {
        assignIdIfMissing(entity);
        // Flowable's own engine code isn't always careful about calling insert() at most once per
        // entity within a command -- TakeOutgoingSequenceFlowsOperation.leaveFlowNode, for one,
        // calls executionEntityManager.insert() a second time on a child execution that
        // createChildExecution() already persisted internally just beforehand (unmodified
        // Flowable source, not something this app's code controls). MyBatis's DbSqlSession
        // tolerates this -- a second insert() of an entity it's already tracking as inserted this
        // session is a no-op there -- so this needs the same guard, or the second call's raw SQL
        // INSERT collides on the primary key. Identity check (not just id equality), since that's
        // the precise signature of "this exact object already went through insert() this command"
        // -- confirmed live: without this, every multi-outgoing-flow fork failed (Parallel Gateway
        // included, but also a plain activity with two outgoing sequence flows and no gateway at
        // all -- this isn't Parallel-Gateway-specific).
        if (session().getCached(ExecutionEntity.class, entity.getId()) == entity) {
            return;
        }
        jdbcClient().sql("""
                INSERT INTO process_execution
                    (id, revision, process_instance_id, parent_id, root_process_instance_id, super_execution_id,
                     process_definition_id, business_key, activity_id, is_active, is_scope, is_concurrent, is_ended)
                VALUES
                    (:id, :revision, :processInstanceId, :parentId, :rootProcessInstanceId, :superExecutionId,
                     :processDefinitionId, :businessKey, :activityId, :isActive, :isScope, :isConcurrent, :isEnded)
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, Math.max(entity.getRevision(), 1))
                .param("processInstanceId", entity.getProcessInstanceId())
                .param(PARAM_PARENT_ID, entity.getParentId())
                .param("rootProcessInstanceId", entity.getRootProcessInstanceId())
                .param("superExecutionId", entity.getSuperExecutionId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("businessKey", entity.getBusinessKey())
                .param("activityId", entity.getActivityId())
                .param("isActive", entity.isActive())
                .param("isScope", entity.isScope())
                .param("isConcurrent", entity.isConcurrent())
                .param("isEnded", entity.isEnded())
                .update();
        session().cache(ExecutionEntity.class, entity.getId(), entity);
        // See ProcessSession's class comment: the engine frequently mutates this exact object's
        // fields (activityId/currentFlowElement as the token moves, isActive/isEnded, ...) without
        // calling update() again itself -- this ensures whatever the fields are by the end of the
        // command still gets persisted.
        session().registerFlush(ExecutionEntity.class, entity.getId(), entity, () -> update(entity));
    }

    // Real optimistic locking (the workflow persistence layer): Flowable's own
    // parallel-gateway join safety and async-job-claim safety both depend entirely on this
    // REV_-column convention (bytecode-verified against flowable-engine/flowable-job-service) --
    // without it, two concurrently-arriving branches (or two nodes racing to claim the same job)
    // would both silently succeed instead of one losing and retrying.
    @Override
    public ExecutionEntity update(ExecutionEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_execution SET
                    process_instance_id = :processInstanceId, parent_id = :parentId,
                    root_process_instance_id = :rootProcessInstanceId, super_execution_id = :superExecutionId,
                    process_definition_id = :processDefinitionId, business_key = :businessKey,
                    activity_id = :activityId, is_active = :isActive, is_scope = :isScope,
                    is_concurrent = :isConcurrent, is_ended = :isEnded, revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, entity.getRevision())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param(PARAM_PARENT_ID, entity.getParentId())
                .param("rootProcessInstanceId", entity.getRootProcessInstanceId())
                .param("superExecutionId", entity.getSuperExecutionId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("businessKey", entity.getBusinessKey())
                .param("activityId", entity.getActivityId())
                .param("isActive", entity.isActive())
                .param("isScope", entity.isScope())
                .param("isConcurrent", entity.isConcurrent())
                .param("isEnded", entity.isEnded())
                .update();
        if (rowsAffected == 0) {
            throw new org.flowable.common.engine.api.FlowableOptimisticLockingException(
                    "Execution " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(ExecutionEntity.class, entity.getId(), entity);
        return entity;
    }

    // Real Flowable's variable cleanup (ExecutionEntityManagerImpl.deleteVariables, called from
    // deleteRelatedDataForExecution) is, confirmed live via bytecode, only reached from the same
    // explicit-cascade path as the event-subscription cleanup elsewhere in this package
    // (RuntimeService.deleteProcessInstance / deployment-cascade delete) -- never from normal
    // completion. Real Flowable gets away with this because ACT_RU_VARIABLE has a DB-level FK to
    // ACT_RU_EXECUTION; this app deliberately has no FKs (see ProcessPersistenceInitializer), so a
    // process's own variables (greeting, greetingLength, ... in hello-world) would otherwise
    // survive every normal completion forever. Confirmed empirically: helloWorldProcessRunsToCompletion
    // left exactly the 3 variables the diagram sets behind until this was added.
    @Override
    public void delete(String id) {
        List<String> variableIds = jdbcClient().sql("SELECT id FROM process_variable WHERE execution_id = :id")
                .param("id", id)
                .query(String.class)
                .list();
        session().evictAll(VariableInstanceEntity.class, variableIds);
        jdbcClient().sql("DELETE FROM process_variable WHERE execution_id = :id").param("id", id).update();
        jdbcClient().sql("DELETE FROM process_execution WHERE id = :id").param("id", id).update();
        session().evict(ExecutionEntity.class, id);
    }

    @Override
    public void delete(ExecutionEntity entity) {
        // Flowable doesn't itself clean up process-instance-scoped identity links (type
        // "participant", written whenever a user claims/completes a task -- see
        // IdentityLinkDataManagerImpl's class comment) when a process completes normally via its
        // end event, only when explicitly removed via RuntimeService.deleteProcessInstance().
        // Confirmed empirically: they'd otherwise accumulate forever, unlike every other entity in
        // this app, which returns to zero rows once a process instance completes.
        if (entity.getId().equals(entity.getProcessInstanceId())) {
            jdbcClient().sql("DELETE FROM process_task_identitylink WHERE process_instance_id = :id")
                    .param("id", entity.getProcessInstanceId())
                    .update();
        }
        delete(entity.getId());
    }

    @Override
    public ExecutionEntity findSubProcessInstanceBySuperExecutionId(String superExecutionId) {
        return jdbcClient().sql(SELECT + " WHERE super_execution_id = :id")
                .param("id", superExecutionId)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public List<ExecutionEntity> findChildExecutionsByParentExecutionId(String parentExecutionId) {
        return jdbcClient().sql(SELECT + " WHERE parent_id = :id")
                .param("id", parentExecutionId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<ExecutionEntity> findChildExecutionsByProcessInstanceId(String processInstanceId) {
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id AND id <> :id")
                .param("id", processInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<ExecutionEntity> findExecutionsByParentExecutionAndActivityIds(String parentExecutionId, Collection<String> activityIds) {
        return jdbcClient().sql(SELECT + " WHERE parent_id = :parentId AND activity_id IN (:activityIds)")
                .param(PARAM_PARENT_ID, parentExecutionId)
                .param("activityIds", activityIds)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public long findExecutionCountByQueryCriteria(ExecutionQueryImpl executionQuery) {
        return 0;
    }

    @Override
    public List<ExecutionEntity> findExecutionsByQueryCriteria(ExecutionQueryImpl executionQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findProcessInstanceCountByQueryCriteria(ProcessInstanceQueryImpl processInstanceQuery) {
        return 0;
    }

    @Override
    public List<ProcessInstance> findProcessInstanceByQueryCriteria(ProcessInstanceQueryImpl processInstanceQuery) {
        return new ArrayList<>();
    }

    @Override
    public List<ExecutionEntity> findExecutionsByRootProcessInstanceId(String rootProcessInstanceId) {
        return jdbcClient().sql(SELECT + " WHERE root_process_instance_id = :id")
                .param("id", rootProcessInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<ExecutionEntity> findExecutionsByProcessInstanceId(String processInstanceId) {
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id")
                .param("id", processInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<ProcessInstance> findProcessInstanceAndVariablesByQueryCriteria(ProcessInstanceQueryImpl processInstanceQuery) {
        return new ArrayList<>();
    }

    // Both finders below are read-your-own-write dependent: ParallelGatewayActivityBehavior (and
    // anything else that does the same join-counting pattern, e.g. Inclusive Gateway) calls
    // execution.inactivate() -- an in-memory field set only, see ProcessSession's class comment --
    // and then immediately queries by that same is_active flag, in the same command, expecting to
    // see it. flush() here is what makes that write visible to this SELECT before it runs; without
    // it the query still sees whatever is_active was at the start of the command, so a Parallel
    // Gateway join count never reaches its target and the gateway waits forever with no error.
    // Confirmed live: this was a real, reproducible hang, not a hypothetical.
    @Override
    public Collection<ExecutionEntity> findInactiveExecutionsByProcessInstanceId(String processInstanceId) {
        session().flush();
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id AND is_active = FALSE")
                .param("id", processInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public Collection<ExecutionEntity> findInactiveExecutionsByActivityIdAndProcessInstanceId(String activityId, String processInstanceId) {
        session().flush();
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :pid AND activity_id = :aid AND is_active = FALSE")
                .param("pid", processInstanceId)
                .param("aid", activityId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<String> findProcessInstanceIdsByProcessDefinitionId(String processDefinitionId) {
        return jdbcClient().sql("SELECT id FROM process_execution WHERE process_definition_id = :id AND id = process_instance_id")
                .param("id", processDefinitionId)
                .query(String.class)
                .list();
    }

    @Override
    public List<Execution> findExecutionsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public List<ProcessInstance> findProcessInstanceByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findExecutionCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    @Override
    public long countActiveExecutionsByParentId(String parentId) {
        return jdbcClient().sql("SELECT COUNT(*) FROM process_execution WHERE parent_id = :id AND is_active = TRUE")
                .param("id", parentId)
                .query(Long.class)
                .single();
    }

    @Override
    public void updateExecutionTenantIdForDeployment(String deploymentId, String newTenantId) {
        // no-op: tenants aren't modeled in this proof.
    }

    @Override
    public void updateAllExecutionRelatedEntityCountFlags(boolean newValue) {
        // no-op: the "entity count" optimization isn't relevant to this custom persistence.
    }

    @Override
    public void updateProcessInstanceLockTime(String processInstanceId, java.util.Date lockDate, String lockOwner, java.util.Date expirationTime) {
        // no-op: process-instance locking isn't modeled in this proof -- fine for a single
        // synchronous, single-threaded process, would need real support before concurrent access.
    }

    @Override
    public void clearProcessInstanceLockTime(String processInstanceId) {
        // no-op, see updateProcessInstanceLockTime.
    }

    @Override
    public void clearAllProcessInstanceLockTimes(String lockOwner) {
        // no-op, see updateProcessInstanceLockTime.
    }

    private static final String SELECT = """
            SELECT id, revision, process_instance_id, parent_id, root_process_instance_id, super_execution_id,
                   process_definition_id, business_key, activity_id, is_active, is_scope, is_concurrent, is_ended
            FROM process_execution
            """;

    private ExecutionEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        ExecutionEntity cached = session().getCached(ExecutionEntity.class, id);
        if (cached != null) {
            return cached;
        }
        ExecutionEntity mapped = mapRow(rs, rowNum);
        session().cache(ExecutionEntity.class, id, mapped);
        // Same reasoning as insert()'s registerFlush call -- a freshly-loaded execution can be
        // mutated in place just as easily as one this command itself created.
        session().registerFlush(ExecutionEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private ExecutionEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        ExecutionEntityImpl entity = new ExecutionEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setRevision(rs.getInt(COL_REVISION));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setParentId(rs.getString("parent_id"));
        entity.setRootProcessInstanceId(rs.getString("root_process_instance_id"));
        entity.setSuperExecutionId(rs.getString("super_execution_id"));
        entity.setProcessDefinitionId(rs.getString("process_definition_id"));
        entity.setBusinessKey(rs.getString("business_key"));
        entity.setActivityId(rs.getString("activity_id"));
        entity.setActive(rs.getBoolean("is_active"));
        entity.setScope(rs.getBoolean("is_scope"));
        entity.setConcurrent(rs.getBoolean("is_concurrent"));
        entity.setEnded(rs.getBoolean("is_ended"));
        return entity;
    }
}
