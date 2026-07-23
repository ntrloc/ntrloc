package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.flowable.variable.service.impl.InternalVariableInstanceQueryImpl;
import org.flowable.variable.service.impl.VariableInstanceQueryImpl;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntity;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntityImpl;
import org.flowable.variable.service.impl.persistence.entity.data.VariableInstanceDataManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Backs runtime process variables with our own process_variable table. Only the plain-value
// columns this proof's variables actually use (text/long/double, matching String/Integer/etc.)
// are covered -- ByteArrayRef-backed types (serialized objects) aren't persisted here yet.
// Native-query variants and task/CMMN-scope deletes are stubbed -- the trivial process has no
// user tasks and isn't a CMMN case, and nothing in this app issues a native variable query.
// findVariablesInstancesByQuery/findVariablesInstanceByQuery (the InternalVariableInstanceQuery
// path) are NOT stubbed, despite looking like the same kind of "unused query API" -- discovered
// live (2026-07) that this is exactly what ExecutionEntityImpl.loadVariableInstances() and
// getSpecificVariable() call to reload an execution's variables any time they're not already in
// the current command's in-memory cache, which includes every async continuation (a timer firing,
// an async job resuming) -- not just genuine ad-hoc queries. Leaving this stubbed silently made
// every process variable invisible to a script task running after any wait state.
public class VariableInstanceDataManagerImpl extends AbstractProcessDataManager implements VariableInstanceDataManager {

    @Override
    public VariableInstanceEntity create() {
        return new VariableInstanceEntityImpl();
    }

    @Override
    public VariableInstanceEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(VariableInstanceEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_variable
                    (id, execution_id, process_instance_id, name, type_name, text_value, long_value, double_value)
                VALUES
                    (:id, :executionId, :processInstanceId, :name, :typeName, :textValue, :longValue, :doubleValue)
                """)
                .param("id", entity.getId())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("name", entity.getName())
                .param("typeName", entity.getTypeName())
                .param("textValue", entity.getTextValue())
                .param("longValue", entity.getLongValue())
                .param("doubleValue", entity.getDoubleValue())
                .update();
        session().cache(VariableInstanceEntity.class, entity.getId(), entity);
        // See ProcessSession's class comment -- the engine can mutate a variable's value in place
        // without calling update() again itself.
        session().registerFlush(VariableInstanceEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public VariableInstanceEntity update(VariableInstanceEntity entity) {
        jdbcClient().sql("""
                UPDATE process_variable SET
                    execution_id = :executionId, process_instance_id = :processInstanceId, name = :name,
                    type_name = :typeName, text_value = :textValue, long_value = :longValue, double_value = :doubleValue
                WHERE id = :id
                """)
                .param("id", entity.getId())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("name", entity.getName())
                .param("typeName", entity.getTypeName())
                .param("textValue", entity.getTextValue())
                .param("longValue", entity.getLongValue())
                .param("doubleValue", entity.getDoubleValue())
                .update();
        session().cache(VariableInstanceEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_variable WHERE id = :id").param("id", id).update();
        session().evict(VariableInstanceEntity.class, id);
    }

    @Override
    public void delete(VariableInstanceEntity entity) {
        delete(entity.getId());
    }

    @Override
    public List<VariableInstanceEntity> findVariablesInstancesByQuery(InternalVariableInstanceQueryImpl query) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        Map<String, Object> params = internalQueryWhereClause(query, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        List<VariableInstanceEntity> candidates = spec.query(this::cacheOrMap).list();
        // task_id/scope_id/sub_scope_id/scope_type have no columns in process_variable, so a
        // loaded entity never has them set -- query.isRetained() (Flowable's own predicate for
        // this exact query object, already trusted by its in-memory cache-matching path) still
        // applies those criteria correctly against that always-null shape: a query that requires
        // a specific taskId/scopeId correctly excludes everything, one that requires
        // withoutTaskId()/withoutSubScopeId() correctly keeps everything. No bespoke SQL needed
        // for task/CMMN-scope support this app doesn't use.
        return candidates.stream().filter(entity -> query.isRetained(entity, query)).toList();
    }

    @Override
    public VariableInstanceEntity findVariablesInstanceByQuery(InternalVariableInstanceQueryImpl query) {
        List<VariableInstanceEntity> results = findVariablesInstancesByQuery(query);
        return results.isEmpty() ? null : results.get(0);
    }

    private Map<String, Object> internalQueryWhereClause(InternalVariableInstanceQueryImpl query, StringBuilder sql) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (query.getId() != null) {
            sql.append(" AND id = :id");
            params.put("id", query.getId());
        }
        if (query.getExecutionId() != null) {
            sql.append(" AND execution_id = :executionId");
            params.put("executionId", query.getExecutionId());
        }
        if (query.getExecutionIds() != null && !query.getExecutionIds().isEmpty()) {
            sql.append(" AND execution_id IN (:executionIds)");
            params.put("executionIds", query.getExecutionIds());
        }
        if (query.getProcessInstanceId() != null) {
            sql.append(" AND process_instance_id = :processInstanceId");
            params.put("processInstanceId", query.getProcessInstanceId());
        }
        if (query.getName() != null) {
            sql.append(" AND name = :name");
            params.put("name", query.getName());
        }
        if (query.getNames() != null && !query.getNames().isEmpty()) {
            sql.append(" AND name IN (:names)");
            params.put("names", query.getNames());
        }
        return params;
    }

    @Override
    public long findVariableInstanceCountByQueryCriteria(VariableInstanceQueryImpl variableInstanceQuery) {
        return 0;
    }

    @Override
    public List<VariableInstance> findVariableInstancesByQueryCriteria(VariableInstanceQueryImpl variableInstanceQuery) {
        return new ArrayList<>();
    }

    @Override
    public List<VariableInstance> findVariableInstancesByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findVariableInstanceCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    @Override
    public void deleteVariablesByTaskId(String taskId) {
        // no-op: no user tasks in this proof.
    }

    @Override
    public void deleteVariablesByExecutionId(String executionId) {
        jdbcClient().sql("DELETE FROM process_variable WHERE execution_id = :id")
                .param("id", executionId)
                .update();
    }

    @Override
    public void deleteByScopeIdAndScopeType(String scopeId, String scopeType) {
        // no-op: CMMN/scoped variables aren't modeled in this proof.
    }

    @Override
    public void deleteByScopeIdAndScopeTypes(String scopeId, Collection<String> scopeTypes) {
        // no-op, see deleteByScopeIdAndScopeType.
    }

    @Override
    public void deleteBySubScopeIdAndScopeTypes(String subScopeId, Collection<String> scopeTypes) {
        // no-op, see deleteByScopeIdAndScopeType.
    }

    private static final String SELECT = """
            SELECT id, execution_id, process_instance_id, name, type_name, text_value, long_value, double_value
            FROM process_variable
            """;

    private VariableInstanceEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        VariableInstanceEntity cached = session().getCached(VariableInstanceEntity.class, id);
        if (cached != null) {
            return cached;
        }
        VariableInstanceEntity mapped = mapRow(rs, rowNum);
        session().cache(VariableInstanceEntity.class, id, mapped);
        session().registerFlush(VariableInstanceEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private VariableInstanceEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        VariableInstanceEntityImpl entity = new VariableInstanceEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setExecutionId(rs.getString("execution_id"));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setName(rs.getString("name"));
        String typeName = rs.getString("type_name");
        entity.setTypeName(typeName);
        if (typeName != null) {
            entity.setType(CommandContextUtil.getProcessEngineConfiguration().getVariableTypes().getVariableType(typeName));
        }
        entity.setTextValue(rs.getString("text_value"));
        Object longValue = rs.getObject("long_value");
        entity.setLongValue(longValue == null ? null : rs.getLong("long_value"));
        Object doubleValue = rs.getObject("double_value");
        entity.setDoubleValue(doubleValue == null ? null : rs.getDouble("double_value"));
        return entity;
    }
}
