package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.engine.impl.DecisionQueryImpl;
import org.flowable.dmn.engine.impl.persistence.entity.DecisionEntity;
import org.flowable.dmn.engine.impl.persistence.entity.DecisionEntityImpl;
import org.flowable.dmn.engine.impl.persistence.entity.data.DecisionDataManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Backs org.flowable.dmn.api.DmnDecision (a decision/decision-table definition) with our own
// decision_definition table, mirroring ProcessDefinitionDataManagerImpl's column-naming
// convention (process_key -> decision_key) and whereClause() conditions-builder subset -- but
// update() is a plain unconditional UPDATE, not revision-checked: unlike ProcessDefinitionEntity,
// DecisionEntity does not implement Flowable's HasRevision (verified via javap), so there's no
// optimistic-locking column to back. decisionType is intentionally not persisted: verified
// via source inspection of flowable-dmn-engine that DmnParse sets it at parse time but nothing in
// the engine's own evaluation path (ExecuteDecisionBuilder and friends) ever reads it back --
// dispatch is driven by the parsed/cached DMN model, not this column. Tenant/category filters are
// likewise ignored, same reasoning as the process-engine side: nothing in ntrloc sets either.
public class DecisionDataManagerImpl extends AbstractDecisionDataManager implements DecisionDataManager {

    private static final String SELECT = """
            SELECT id, deployment_id, decision_key, name, version, resource_name
            FROM decision_definition
            """;

    @Override
    public DecisionEntity create() {
        return new DecisionEntityImpl();
    }

    @Override
    public DecisionEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(DecisionEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO decision_definition (id, deployment_id, decision_key, name, version, resource_name)
                VALUES (:id, :deploymentId, :key, :name, :version, :resourceName)
                """)
                .param("id", entity.getId())
                .param("deploymentId", entity.getDeploymentId())
                .param("key", entity.getKey())
                .param("name", entity.getName())
                .param("version", entity.getVersion())
                .param("resourceName", entity.getResourceName())
                .update();
        session().cache(DecisionEntity.class, entity.getId(), entity);
    }

    // DecisionEntity, unlike ProcessDefinitionEntity, does not implement Flowable's HasRevision --
    // verified via javap, no getRevision()/setRevision()/getRevisionNext() exist on it -- so this
    // is a plain unconditional UPDATE, matching DeploymentDataManagerImpl's own update() rather
    // than ProcessDefinitionDataManagerImpl's revision-checked one.
    @Override
    public DecisionEntity update(DecisionEntity entity) {
        jdbcClient().sql("""
                UPDATE decision_definition
                SET deployment_id = :deploymentId, decision_key = :key, name = :name,
                    version = :version, resource_name = :resourceName
                WHERE id = :id
                """)
                .param("id", entity.getId())
                .param("deploymentId", entity.getDeploymentId())
                .param("key", entity.getKey())
                .param("name", entity.getName())
                .param("version", entity.getVersion())
                .param("resourceName", entity.getResourceName())
                .update();
        session().cache(DecisionEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM decision_definition WHERE id = :id").param("id", id).update();
        session().evict(DecisionEntity.class, id);
    }

    @Override
    public void delete(DecisionEntity entity) {
        delete(entity.getId());
    }

    @Override
    public DecisionEntity findLatestDecisionByKey(String key) {
        return jdbcClient().sql(SELECT + " WHERE decision_key = :key ORDER BY version DESC LIMIT 1")
                .param("key", key)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public DecisionEntity findLatestDecisionByKeyAndTenantId(String key, String tenantId) {
        return findLatestDecisionByKey(key);
    }

    @Override
    public DecisionEntity findLatestDecisionByKeyAndParentDeploymentId(String key, String parentDeploymentId) {
        return findLatestDecisionByKey(key);
    }

    @Override
    public DecisionEntity findLatestDecisionByKeyParentDeploymentIdAndTenantId(String key, String parentDeploymentId, String tenantId) {
        return findLatestDecisionByKey(key);
    }

    @Override
    public void deleteDecisionsByDeploymentId(String deploymentId) {
        jdbcClient().sql("DELETE FROM decision_definition WHERE deployment_id = :deploymentId")
                .param("deploymentId", deploymentId)
                .update();
    }

    // Same filter subset as ProcessDefinitionDataManagerImpl.whereClause() -- id/ids, deploymentId,
    // key/keyLike, name/nameLike, version comparisons, latest-per-key. No ORDER BY support.
    @Override
    public List<DmnDecision> findDecisionsByQueryCriteria(DecisionQueryImpl query) {
        StringBuilder sql = new StringBuilder(SELECT);
        Map<String, Object> params = whereClause(query, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return new ArrayList<>(spec.query(this::cacheOrMap).list());
    }

    @Override
    public long findDecisionCountByQueryCriteria(DecisionQueryImpl query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM decision_definition");
        Map<String, Object> params = whereClause(query, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query(Long.class).single();
    }

    private Map<String, Object> whereClause(DecisionQueryImpl query, StringBuilder sql) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>();

        if (query.getId() != null) {
            conditions.add("id = :id");
            params.put("id", query.getId());
        }
        if (query.getIds() != null && !query.getIds().isEmpty()) {
            conditions.add("id IN (:ids)");
            params.put("ids", query.getIds());
        }
        if (query.getDeploymentId() != null) {
            conditions.add("deployment_id = :deploymentId");
            params.put("deploymentId", query.getDeploymentId());
        }
        if (query.getKey() != null) {
            conditions.add("decision_key = :key");
            params.put("key", query.getKey());
        }
        if (query.getKeyLike() != null) {
            conditions.add("decision_key LIKE :keyLike");
            params.put("keyLike", query.getKeyLike());
        }
        if (query.getName() != null) {
            conditions.add("name = :name");
            params.put("name", query.getName());
        }
        if (query.getNameLike() != null) {
            conditions.add("name LIKE :nameLike");
            params.put("nameLike", query.getNameLike());
        }
        if (query.getVersion() != null) {
            conditions.add("version = :version");
            params.put("version", query.getVersion());
        }
        if (query.isLatest()) {
            conditions.add("version = (SELECT MAX(dd2.version) FROM decision_definition dd2 WHERE dd2.decision_key = decision_definition.decision_key)");
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        return params;
    }

    @Override
    public DecisionEntity findDecisionByDeploymentAndKey(String deploymentId, String key) {
        return jdbcClient().sql(SELECT + " WHERE deployment_id = :deploymentId AND decision_key = :key")
                .param("deploymentId", deploymentId)
                .param("key", key)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public DecisionEntity findDecisionByDeploymentAndKeyAndTenantId(String deploymentId, String key, String tenantId) {
        return findDecisionByDeploymentAndKey(deploymentId, key);
    }

    @Override
    public DecisionEntity findDecisionByKeyAndVersion(String key, Integer version) {
        return jdbcClient().sql(SELECT + " WHERE decision_key = :key AND version = :version")
                .param("key", key)
                .param("version", version)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public DecisionEntity findDecisionByKeyAndVersionAndTenantId(String key, Integer version, String tenantId) {
        return findDecisionByKeyAndVersion(key, version);
    }

    @Override
    public List<DmnDecision> findDecisionsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findDecisionCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    @Override
    public void updateDecisionTenantIdForDeployment(String deploymentId, String newTenantId) {
        // no-op: tenants aren't modeled in this app.
    }

    private DecisionEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        DecisionEntity cached = session().getCached(DecisionEntity.class, id);
        if (cached != null) {
            return cached;
        }
        DecisionEntity mapped = mapRow(rs, rowNum);
        session().cache(DecisionEntity.class, id, mapped);
        return mapped;
    }

    private DecisionEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        DecisionEntityImpl entity = new DecisionEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setDeploymentId(rs.getString("deployment_id"));
        entity.setKey(rs.getString("decision_key"));
        entity.setName(rs.getString("name"));
        entity.setVersion(rs.getInt("version"));
        entity.setResourceName(rs.getString("resource_name"));
        return entity;
    }
}
