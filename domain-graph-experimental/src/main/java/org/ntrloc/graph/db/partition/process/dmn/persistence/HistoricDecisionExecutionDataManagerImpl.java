package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.api.DmnHistoricDecisionExecution;
import org.flowable.dmn.engine.impl.HistoricDecisionExecutionQueryImpl;
import org.flowable.dmn.engine.impl.persistence.entity.HistoricDecisionExecutionEntity;
import org.flowable.dmn.engine.impl.persistence.entity.HistoricDecisionExecutionEntityImpl;
import org.flowable.dmn.engine.impl.persistence.entity.data.HistoricDecisionExecutionDataManager;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// Backs the audit-trail row DmnActivityBehavior.executeWithAuditTrail() writes on every decision
// evaluation with our own decision_historic_execution table. insert()/findById() must be real --
// this is unconditionally written by every live DMN Task evaluation, not optional cleanup -- but
// nothing in ntrloc's admin UI surfaces this history yet, so query-criteria/native-query finders
// are stubbed empty, matching this package's convention for not-yet-exercised query surfaces.
public class HistoricDecisionExecutionDataManagerImpl extends AbstractDecisionDataManager implements HistoricDecisionExecutionDataManager {

    private static final String SELECT = """
            SELECT id, decision_definition_id, deployment_id, start_time, end_time, instance_id,
                   execution_id, activity_id, scope_type, failed, tenant_id, execution_json
            FROM decision_historic_execution
            """;

    @Override
    public HistoricDecisionExecutionEntity create() {
        return new HistoricDecisionExecutionEntityImpl();
    }

    @Override
    public HistoricDecisionExecutionEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(HistoricDecisionExecutionEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO decision_historic_execution
                    (id, decision_definition_id, deployment_id, start_time, end_time, instance_id,
                     execution_id, activity_id, scope_type, failed, tenant_id, execution_json)
                VALUES
                    (:id, :decisionDefinitionId, :deploymentId, :startTime, :endTime, :instanceId,
                     :executionId, :activityId, :scopeType, :failed, :tenantId, :executionJson)
                """)
                .param("id", entity.getId())
                .param("decisionDefinitionId", entity.getDecisionDefinitionId())
                .param("deploymentId", entity.getDeploymentId())
                .param("startTime", toTimestamp(entity.getStartTime()))
                .param("endTime", toTimestamp(entity.getEndTime()))
                .param("instanceId", entity.getInstanceId())
                .param("executionId", entity.getExecutionId())
                .param("activityId", entity.getActivityId())
                .param("scopeType", entity.getScopeType())
                .param("failed", entity.isFailed())
                .param("tenantId", entity.getTenantId())
                .param("executionJson", entity.getExecutionJson())
                .update();
        session().cache(HistoricDecisionExecutionEntity.class, entity.getId(), entity);
    }

    @Override
    public HistoricDecisionExecutionEntity update(HistoricDecisionExecutionEntity entity) {
        jdbcClient().sql("""
                UPDATE decision_historic_execution SET
                    decision_definition_id = :decisionDefinitionId, deployment_id = :deploymentId,
                    start_time = :startTime, end_time = :endTime, instance_id = :instanceId,
                    execution_id = :executionId, activity_id = :activityId, scope_type = :scopeType,
                    failed = :failed, tenant_id = :tenantId, execution_json = :executionJson
                WHERE id = :id
                """)
                .param("id", entity.getId())
                .param("decisionDefinitionId", entity.getDecisionDefinitionId())
                .param("deploymentId", entity.getDeploymentId())
                .param("startTime", toTimestamp(entity.getStartTime()))
                .param("endTime", toTimestamp(entity.getEndTime()))
                .param("instanceId", entity.getInstanceId())
                .param("executionId", entity.getExecutionId())
                .param("activityId", entity.getActivityId())
                .param("scopeType", entity.getScopeType())
                .param("failed", entity.isFailed())
                .param("tenantId", entity.getTenantId())
                .param("executionJson", entity.getExecutionJson())
                .update();
        session().cache(HistoricDecisionExecutionEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM decision_historic_execution WHERE id = :id").param("id", id).update();
        session().evict(HistoricDecisionExecutionEntity.class, id);
    }

    @Override
    public void delete(HistoricDecisionExecutionEntity entity) {
        delete(entity.getId());
    }

    @Override
    public void deleteHistoricDecisionExecutionsByDeploymentId(String deploymentId) {
        jdbcClient().sql("DELETE FROM decision_historic_execution WHERE deployment_id = :deploymentId")
                .param("deploymentId", deploymentId)
                .update();
    }

    @Override
    public List<DmnHistoricDecisionExecution> findHistoricDecisionExecutionsByQueryCriteria(HistoricDecisionExecutionQueryImpl query) {
        return new ArrayList<>();
    }

    @Override
    public long findHistoricDecisionExecutionCountByQueryCriteria(HistoricDecisionExecutionQueryImpl query) {
        return 0;
    }

    @Override
    public List<DmnHistoricDecisionExecution> findHistoricDecisionExecutionsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findHistoricDecisionExecutionCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    @Override
    public void delete(HistoricDecisionExecutionQueryImpl query) {
        // no-op: nothing in ntrloc issues a bulk-delete-by-query against this history yet.
    }

    @Override
    public void bulkDeleteHistoricDecisionExecutionsByInstanceIdsAndScopeType(Collection<String> instanceIds, String scopeType) {
        // no-op, see delete(HistoricDecisionExecutionQueryImpl) above.
    }

    private HistoricDecisionExecutionEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        HistoricDecisionExecutionEntity cached = session().getCached(HistoricDecisionExecutionEntity.class, id);
        if (cached != null) {
            return cached;
        }
        HistoricDecisionExecutionEntity mapped = mapRow(rs, rowNum);
        session().cache(HistoricDecisionExecutionEntity.class, id, mapped);
        return mapped;
    }

    private HistoricDecisionExecutionEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        HistoricDecisionExecutionEntityImpl entity = new HistoricDecisionExecutionEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setDecisionDefinitionId(rs.getString("decision_definition_id"));
        entity.setDeploymentId(rs.getString("deployment_id"));
        entity.setStartTime(fromTimestamp(rs.getTimestamp("start_time")));
        entity.setEndTime(fromTimestamp(rs.getTimestamp("end_time")));
        entity.setInstanceId(rs.getString("instance_id"));
        entity.setExecutionId(rs.getString("execution_id"));
        entity.setActivityId(rs.getString("activity_id"));
        entity.setScopeType(rs.getString("scope_type"));
        entity.setFailed(rs.getBoolean("failed"));
        entity.setTenantId(rs.getString("tenant_id"));
        entity.setExecutionJson(rs.getString("execution_json"));
        return entity;
    }

    private static Timestamp toTimestamp(java.util.Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private static java.util.Date fromTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : new java.util.Date(timestamp.getTime());
    }
}
