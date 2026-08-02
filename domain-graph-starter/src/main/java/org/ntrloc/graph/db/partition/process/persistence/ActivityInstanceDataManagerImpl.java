package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.impl.ActivityInstanceQueryImpl;
import org.flowable.engine.impl.persistence.entity.ActivityInstanceEntity;
import org.flowable.engine.impl.persistence.entity.ActivityInstanceEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ActivityInstanceDataManager;
import org.flowable.engine.runtime.ActivityInstance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs live "currently active activity" tracking (ACT_RU_ACTINST) with our own
// process_activity_instance table -- mandatory, not optional cleanup: Flowable's
// ContinueProcessOperation calls recordActivityStart/recordActivityEnd unconditionally on every
// activity transition, no history-level gate, so a real implementation is needed the moment
// Flowable's own default MyBatis table stops existing (the workflow persistence layer).
// Query-object/native-query variants are stubbed -- nothing in this app queries activity instances
// yet, only the finders recordActivityStart/recordActivityEnd/recordTaskCreated actually call.
// java.util.Date is unavoidable here: overrides Flowable's own Date-based entity API.
@SuppressWarnings("java:S2143")
public class ActivityInstanceDataManagerImpl extends AbstractProcessDataManager implements ActivityInstanceDataManager {

    private static final String COL_REVISION = "revision";
    private static final String PARAM_EXECUTION_ID = "executionId";
    private static final String PARAM_ACTIVITY_ID = "activityId";
    private static final String PARAM_TASK_ID = "taskId";
    private static final String PARAM_ASSIGNEE = "assignee";

    @Override
    public ActivityInstanceEntity create() {
        return new ActivityInstanceEntityImpl();
    }

    @Override
    public ActivityInstanceEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(ActivityInstanceEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_activity_instance
                    (id, revision, process_instance_id, process_definition_id, execution_id, activity_id,
                     activity_name, activity_type, task_id, assignee, completed_by, start_time, end_time,
                     duration_millis, transaction_order, delete_reason, called_process_instance_id)
                VALUES
                    (:id, :revision, :processInstanceId, :processDefinitionId, :executionId, :activityId,
                     :activityName, :activityType, :taskId, :assignee, :completedBy, :startTime, :endTime,
                     :durationMillis, :transactionOrder, :deleteReason, :calledProcessInstanceId)
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, Math.max(entity.getRevision(), 1))
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param(PARAM_EXECUTION_ID, entity.getExecutionId())
                .param(PARAM_ACTIVITY_ID, entity.getActivityId())
                .param("activityName", entity.getActivityName())
                .param("activityType", entity.getActivityType())
                .param(PARAM_TASK_ID, entity.getTaskId())
                .param(PARAM_ASSIGNEE, entity.getAssignee())
                .param("completedBy", entity.getCompletedBy())
                .param("startTime", toTimestamp(entity.getStartTime() == null ? new Date() : entity.getStartTime()))
                .param("endTime", toTimestamp(entity.getEndTime()))
                .param("durationMillis", entity.getDurationInMillis())
                .param("transactionOrder", entity.getTransactionOrder())
                .param("deleteReason", entity.getDeleteReason())
                .param("calledProcessInstanceId", entity.getCalledProcessInstanceId())
                .update();
        session().cache(ActivityInstanceEntity.class, entity.getId(), entity);
        session().registerFlush(ActivityInstanceEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public ActivityInstanceEntity update(ActivityInstanceEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_activity_instance SET
                    process_instance_id = :processInstanceId, process_definition_id = :processDefinitionId,
                    execution_id = :executionId, activity_id = :activityId, activity_name = :activityName,
                    activity_type = :activityType, task_id = :taskId, assignee = :assignee,
                    completed_by = :completedBy, start_time = :startTime, end_time = :endTime,
                    duration_millis = :durationMillis, transaction_order = :transactionOrder,
                    delete_reason = :deleteReason, called_process_instance_id = :calledProcessInstanceId,
                    revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, entity.getRevision())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param(PARAM_EXECUTION_ID, entity.getExecutionId())
                .param(PARAM_ACTIVITY_ID, entity.getActivityId())
                .param("activityName", entity.getActivityName())
                .param("activityType", entity.getActivityType())
                .param(PARAM_TASK_ID, entity.getTaskId())
                .param(PARAM_ASSIGNEE, entity.getAssignee())
                .param("completedBy", entity.getCompletedBy())
                .param("startTime", toTimestamp(entity.getStartTime()))
                .param("endTime", toTimestamp(entity.getEndTime()))
                .param("durationMillis", entity.getDurationInMillis())
                .param("transactionOrder", entity.getTransactionOrder())
                .param("deleteReason", entity.getDeleteReason())
                .param("calledProcessInstanceId", entity.getCalledProcessInstanceId())
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException(
                    "ActivityInstance " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(ActivityInstanceEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_activity_instance WHERE id = :id").param("id", id).update();
        session().evict(ActivityInstanceEntity.class, id);
    }

    @Override
    public void delete(ActivityInstanceEntity entity) {
        delete(entity.getId());
    }

    // recordActivityEnd's "find the still-open instance to close out" lookup.
    @Override
    public List<ActivityInstanceEntity> findUnfinishedActivityInstancesByExecutionAndActivityId(String executionId, String activityId) {
        return jdbcClient().sql(SELECT + " WHERE execution_id = :executionId AND activity_id = :activityId AND end_time IS NULL")
                .param(PARAM_EXECUTION_ID, executionId)
                .param(PARAM_ACTIVITY_ID, activityId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<ActivityInstanceEntity> findActivityInstancesByExecutionIdAndActivityId(String executionId, String activityId) {
        return jdbcClient().sql(SELECT + " WHERE execution_id = :executionId AND activity_id = :activityId")
                .param(PARAM_EXECUTION_ID, executionId)
                .param(PARAM_ACTIVITY_ID, activityId)
                .query(this::cacheOrMap)
                .list();
    }

    // recordTaskCreated's lookup, associating a User Task's own activity instance row with the
    // task once the task itself is created.
    @Override
    public ActivityInstanceEntity findActivityInstanceByTaskId(String taskId) {
        return jdbcClient().sql(SELECT + " WHERE task_id = :taskId")
                .param(PARAM_TASK_ID, taskId)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public List<ActivityInstanceEntity> findActivityInstancesByProcessInstanceId(String processInstanceId, boolean includeDeleted) {
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id")
                .param("id", processInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    // Evicts every matching row from the session before deleting -- see ProcessSession.evictAll's
    // class comment. Without this, an activity instance ended earlier in the same command (e.g. the
    // reviewGreeting user task, marked ended in-memory when its owning task completes) still has a
    // pending flush-update when the process instance itself finishes and this runs; flush() would
    // then try to UPDATE a row this DELETE already removed and throw a false optimistic-lock error.
    @Override
    public void deleteActivityInstancesByProcessInstanceId(String processInstanceId) {
        List<String> ids = jdbcClient().sql("SELECT id FROM process_activity_instance WHERE process_instance_id = :id")
                .param("id", processInstanceId)
                .query(String.class)
                .list();
        session().evictAll(ActivityInstanceEntity.class, ids);
        jdbcClient().sql("DELETE FROM process_activity_instance WHERE process_instance_id = :id")
                .param("id", processInstanceId)
                .update();
    }

    // Query-object/native-query surfaces: stubbed, matching this package's convention -- nothing
    // in this app calls RuntimeService.createActivityInstanceQuery() yet.
    @Override
    public long findActivityInstanceCountByQueryCriteria(ActivityInstanceQueryImpl activityInstanceQuery) {
        return 0;
    }

    @Override
    public List<ActivityInstance> findActivityInstancesByQueryCriteria(ActivityInstanceQueryImpl activityInstanceQuery) {
        return new ArrayList<>();
    }

    @Override
    public List<ActivityInstance> findActivityInstancesByNativeQuery(java.util.Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findActivityInstanceCountByNativeQuery(java.util.Map<String, Object> parameterMap) {
        return 0;
    }

    private static final String SELECT = """
            SELECT id, revision, process_instance_id, process_definition_id, execution_id, activity_id,
                   activity_name, activity_type, task_id, assignee, completed_by, start_time, end_time,
                   duration_millis, transaction_order, delete_reason, called_process_instance_id
            FROM process_activity_instance
            """;

    private ActivityInstanceEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        ActivityInstanceEntity cached = session().getCached(ActivityInstanceEntity.class, id);
        if (cached != null) {
            return cached;
        }
        ActivityInstanceEntity mapped = mapRow(rs);
        session().cache(ActivityInstanceEntity.class, id, mapped);
        session().registerFlush(ActivityInstanceEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private ActivityInstanceEntity mapRow(ResultSet rs) throws SQLException {
        ActivityInstanceEntityImpl entity = new ActivityInstanceEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setRevision(rs.getInt(COL_REVISION));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setProcessDefinitionId(rs.getString("process_definition_id"));
        entity.setExecutionId(rs.getString("execution_id"));
        entity.setActivityId(rs.getString("activity_id"));
        entity.setActivityName(rs.getString("activity_name"));
        entity.setActivityType(rs.getString("activity_type"));
        entity.setTaskId(rs.getString("task_id"));
        entity.setAssignee(rs.getString(PARAM_ASSIGNEE));
        entity.setCompletedBy(rs.getString("completed_by"));
        entity.setStartTime(fromTimestamp(rs.getTimestamp("start_time")));
        entity.setEndTime(fromTimestamp(rs.getTimestamp("end_time")));
        long durationMillis = rs.getLong("duration_millis");
        entity.setDurationInMillis(rs.wasNull() ? null : durationMillis);
        int transactionOrder = rs.getInt("transaction_order");
        entity.setTransactionOrder(rs.wasNull() ? null : transactionOrder);
        entity.setDeleteReason(rs.getString("delete_reason"));
        entity.setCalledProcessInstanceId(rs.getString("called_process_instance_id"));
        return entity;
    }

    private static Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private static Date fromTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : new Date(timestamp.getTime());
    }
}
