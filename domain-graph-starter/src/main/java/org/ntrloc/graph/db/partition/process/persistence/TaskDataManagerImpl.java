package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.task.api.Task;
import org.flowable.task.service.impl.TaskQueryImpl;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntityImpl;
import org.flowable.task.service.impl.persistence.entity.data.TaskDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Backs org.flowable.task.api.Task with our own process_task table -- the same JdbcClient-backed
// pattern as every other custom DataManager in this package (see ExecutionDataManagerImpl). Covers
// exactly what TaskAdminController's queries need (see whereClause()); everything this app doesn't
// exercise (CMMN scope finders, native queries, parent-task queries, tenant/entity-count
// bookkeeping) stubs to empty/no-op, matching ProcessDefinitionDataManagerImpl's own precedent.
public class TaskDataManagerImpl extends AbstractProcessDataManager implements TaskDataManager {

    private static final String PARAM_ASSIGNEE = "assignee";

    @Override
    public TaskEntity create() {
        return new TaskEntityImpl();
    }

    @Override
    public TaskEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(TaskEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_task
                    (id, name, assignee, create_time, execution_id, process_instance_id,
                     process_definition_id, task_definition_key)
                VALUES
                    (:id, :name, :assignee, :createTime, :executionId, :processInstanceId,
                     :processDefinitionId, :taskDefinitionKey)
                """)
                .param("id", entity.getId())
                .param("name", entity.getName())
                .param(PARAM_ASSIGNEE, entity.getAssignee())
                .param("createTime", entity.getCreateTime())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("taskDefinitionKey", entity.getTaskDefinitionKey())
                .update();
        session().cache(TaskEntity.class, entity.getId(), entity);
        // See ProcessSession's class comment -- the engine mutates a task's fields (assignee via
        // claim(), etc.) in place without necessarily calling update() again itself.
        session().registerFlush(TaskEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public TaskEntity update(TaskEntity entity) {
        jdbcClient().sql("""
                UPDATE process_task SET
                    name = :name, assignee = :assignee, create_time = :createTime,
                    execution_id = :executionId, process_instance_id = :processInstanceId,
                    process_definition_id = :processDefinitionId, task_definition_key = :taskDefinitionKey
                WHERE id = :id
                """)
                .param("id", entity.getId())
                .param("name", entity.getName())
                .param(PARAM_ASSIGNEE, entity.getAssignee())
                .param("createTime", entity.getCreateTime())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("taskDefinitionKey", entity.getTaskDefinitionKey())
                .update();
        session().cache(TaskEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_task WHERE id = :id").param("id", id).update();
        session().evict(TaskEntity.class, id);
    }

    @Override
    public void delete(TaskEntity entity) {
        delete(entity.getId());
    }

    @Override
    public List<TaskEntity> findTasksByExecutionId(String executionId) {
        return jdbcClient().sql(SELECT + " WHERE execution_id = :id")
                .param("id", executionId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<TaskEntity> findTasksByProcessInstanceId(String processInstanceId) {
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id")
                .param("id", processInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<TaskEntity> findTasksByScopeIdAndScopeType(String scopeId, String scopeType) {
        return new ArrayList<>();
    }

    @Override
    public List<TaskEntity> findTasksBySubScopeIdAndScopeType(String subScopeId, String scopeType) {
        return new ArrayList<>();
    }

    @Override
    public List<Task> findTasksByQueryCriteria(TaskQueryImpl taskQuery) {
        StringBuilder sql = new StringBuilder(SELECT);
        Map<String, Object> params = whereClause(taskQuery, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return new ArrayList<>(spec.query(this::cacheOrMap).list());
    }

    @Override
    public List<Task> findTasksWithRelatedEntitiesByQueryCriteria(TaskQueryImpl taskQuery) {
        return findTasksByQueryCriteria(taskQuery);
    }

    @Override
    public long findTaskCountByQueryCriteria(TaskQueryImpl taskQuery) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM process_task");
        Map<String, Object> params = whereClause(taskQuery, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query(Long.class).single();
    }

    @Override
    public List<Task> findTasksByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findTaskCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    @Override
    public List<Task> findTasksByParentTaskId(String parentTaskId) {
        return new ArrayList<>();
    }

    @Override
    public void updateTaskTenantIdForDeployment(String deploymentId, String newTenantId) {
        // no-op: tenants aren't modeled in this proof.
    }

    @Override
    public void updateAllTaskRelatedEntityCountFlags(boolean newValue) {
        // no-op: the "entity count" optimization isn't relevant to this custom persistence.
    }

    @Override
    public void deleteTasksByExecutionId(String executionId) {
        jdbcClient().sql("DELETE FROM process_task WHERE execution_id = :id")
                .param("id", executionId)
                .update();
    }

    // Covers exactly what TaskAdminController's queries need: a plain taskId() lookup (claim's
    // re-query), and assignee/candidateUser/candidateGroupIn -- the latter two check
    // process_task_identitylink via EXISTS, since candidates aren't columns on the task itself.
    // Flowable's .or()...endOr() surfaces as getOrQueryObjects(): every sub-query in that list gets
    // its own applicable conditions ANDed together, and the sub-queries themselves are ORed as one
    // parenthesized group alongside whatever top-level (AND) conditions are also present -- robust
    // to however Flowable happens to bucket individual .taskXxx() calls within the or() block,
    // rather than assuming exactly one field per sub-object.
    private Map<String, Object> whereClause(TaskQueryImpl query, StringBuilder sql) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>(fieldConditions(query, params, "top"));

        // Confirmed empirically (not assumed): Flowable's .or()...endOr() puts every chained
        // .taskAssignee()/.taskCandidateUser()/.taskCandidateGroupIn() call onto a *single* shared
        // sub-query object, not one object per call -- so every field found across every sub-object
        // in this list is one alternative in the same flat OR group, never ANDed with siblings on
        // the same object. (An earlier version of this method ANDed same-object fields together,
        // which silently turned "assignee OR candidateUser OR candidateGroup" into "assignee AND
        // candidateUser AND candidateGroup" -- nothing ever matched all three at once, so every
        // non-superuser task query returned empty.)
        List<TaskQueryImpl> orObjects = query.getOrQueryObjects();
        if (orObjects != null && !orObjects.isEmpty()) {
            List<String> orGroup = new ArrayList<>();
            int i = 0;
            for (TaskQueryImpl sub : orObjects) {
                orGroup.addAll(fieldConditions(sub, params, "or" + i++));
            }
            if (!orGroup.isEmpty()) {
                conditions.add("(" + String.join(" OR ", orGroup) + ")");
            }
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        return params;
    }

    private List<String> fieldConditions(TaskQueryImpl query, Map<String, Object> params, String paramSuffix) {
        List<String> conditions = new ArrayList<>();
        if (query.getTaskId() != null) {
            String key = "taskId" + paramSuffix;
            params.put(key, query.getTaskId());
            conditions.add("id = :" + key);
        }
        if (query.getAssignee() != null) {
            String key = PARAM_ASSIGNEE + paramSuffix;
            params.put(key, query.getAssignee());
            conditions.add("assignee = :" + key);
        }
        if (query.getCandidateUser() != null) {
            String key = "candidateUser" + paramSuffix;
            params.put(key, query.getCandidateUser());
            conditions.add("""
                    EXISTS (SELECT 1 FROM process_task_identitylink l
                            WHERE l.task_id = process_task.id AND l.type = 'candidate' AND l.user_id = :%s)
                    """.formatted(key));
        }
        if (query.getCandidateGroups() != null && !query.getCandidateGroups().isEmpty()) {
            String key = "candidateGroups" + paramSuffix;
            params.put(key, query.getCandidateGroups());
            conditions.add("""
                    EXISTS (SELECT 1 FROM process_task_identitylink l
                            WHERE l.task_id = process_task.id AND l.type = 'candidate' AND l.group_id IN (:%s))
                    """.formatted(key));
        }
        return conditions;
    }

    private static final String SELECT = """
            SELECT id, name, assignee, create_time, execution_id, process_instance_id,
                   process_definition_id, task_definition_key
            FROM process_task
            """;

    private TaskEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        TaskEntity cached = session().getCached(TaskEntity.class, id);
        if (cached != null) {
            return cached;
        }
        TaskEntity mapped = mapRow(rs);
        session().cache(TaskEntity.class, id, mapped);
        session().registerFlush(TaskEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private TaskEntity mapRow(ResultSet rs) throws SQLException {
        TaskEntityImpl entity = new TaskEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setName(rs.getString("name"));
        entity.setAssignee(rs.getString(PARAM_ASSIGNEE));
        entity.setCreateTime(rs.getTimestamp("create_time"));
        entity.setExecutionId(rs.getString("execution_id"));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setProcessDefinitionId(rs.getString("process_definition_id"));
        entity.setTaskDefinitionKey(rs.getString("task_definition_key"));
        return entity;
    }
}
