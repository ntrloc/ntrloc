package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.impl.ProcessDefinitionQueryImpl;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ProcessDefinitionDataManager;
import org.flowable.engine.repository.ProcessDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Backs org.flowable.engine.repository.ProcessDefinition with our own process_definition table.
// Tenant/derived-process/native-query variants are stubbed (return null/empty) -- this proof
// deploys exactly one, single-tenant, non-derived process definition. Optimistic locking (the
// revision column) is enforced in update() -- see the workflow persistence layer.
public class ProcessDefinitionDataManagerImpl extends AbstractProcessDataManager implements ProcessDefinitionDataManager {

    private static final String PARAM_DEPLOYMENT_ID = "deploymentId";
    private static final String PARAM_VERSION = "version";
    private static final String COL_REVISION = "revision";

    @Override
    public ProcessDefinitionEntity create() {
        return new ProcessDefinitionEntityImpl();
    }

    @Override
    public ProcessDefinitionEntity findById(String id) {
        return jdbcClient().sql("""
                SELECT id, deployment_id, process_key, name, version, resource_name, revision
                FROM process_definition WHERE id = :id
                """)
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(ProcessDefinitionEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_definition (id, deployment_id, process_key, name, version, resource_name, revision)
                VALUES (:id, :deploymentId, :key, :name, :version, :resourceName, :revision)
                """)
                .param("id", entity.getId())
                .param(PARAM_DEPLOYMENT_ID, entity.getDeploymentId())
                .param("key", entity.getKey())
                .param("name", entity.getName())
                .param(PARAM_VERSION, entity.getVersion())
                .param("resourceName", entity.getResourceName())
                .param(COL_REVISION, Math.max(entity.getRevision(), 1))
                .update();
        session().cache(ProcessDefinitionEntity.class, entity.getId(), entity);
    }

    @Override
    public ProcessDefinitionEntity update(ProcessDefinitionEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_definition
                SET deployment_id = :deploymentId, process_key = :key, name = :name,
                    version = :version, resource_name = :resourceName, revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, entity.getRevision())
                .param(PARAM_DEPLOYMENT_ID, entity.getDeploymentId())
                .param("key", entity.getKey())
                .param("name", entity.getName())
                .param(PARAM_VERSION, entity.getVersion())
                .param("resourceName", entity.getResourceName())
                .update();
        if (rowsAffected == 0) {
            throw new org.flowable.common.engine.api.FlowableOptimisticLockingException(
                    "ProcessDefinition " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(ProcessDefinitionEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_definition WHERE id = :id").param("id", id).update();
        session().evict(ProcessDefinitionEntity.class, id);
    }

    @Override
    public void delete(ProcessDefinitionEntity entity) {
        delete(entity.getId());
    }

    @Override
    public ProcessDefinitionEntity findLatestProcessDefinitionByKey(String key) {
        return jdbcClient().sql("""
                SELECT id, deployment_id, process_key, name, version, resource_name, revision
                FROM process_definition WHERE process_key = :key ORDER BY version DESC LIMIT 1
                """)
                .param("key", key)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public ProcessDefinitionEntity findLatestProcessDefinitionByKeyAndTenantId(String key, String tenantId) {
        return findLatestProcessDefinitionByKey(key);
    }

    @Override
    public ProcessDefinitionEntity findLatestDerivedProcessDefinitionByKey(String key) {
        return null;
    }

    @Override
    public ProcessDefinitionEntity findLatestDerivedProcessDefinitionByKeyAndTenantId(String key, String tenantId) {
        return null;
    }

    @Override
    public void deleteProcessDefinitionsByDeploymentId(String deploymentId) {
        jdbcClient().sql("DELETE FROM process_definition WHERE deployment_id = :deploymentId")
                .param(PARAM_DEPLOYMENT_ID, deploymentId)
                .update();
    }

    // Supports the filters an admin "list deployed process definitions" screen actually needs
    // (id/ids, deploymentId, key/keyLike, name/nameLike, version comparisons, latest-per-key) --
    // not full RepositoryService query-criteria parity. Category and tenant filters are silently
    // ignored: nothing in this app sets a category or a non-null tenant on a process definition, so
    // there's no case where honoring them would currently change the result. No ORDER BY support
    // (orderBy*() on the query builder is a no-op here) -- callers get id order; add real ordering
    // if/when a caller needs a specific one.
    @Override
    public List<ProcessDefinition> findProcessDefinitionsByQueryCriteria(ProcessDefinitionQueryImpl query) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, deployment_id, process_key, name, version, resource_name, revision
                FROM process_definition
                """);
        Map<String, Object> params = whereClause(query, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return new ArrayList<>(spec.query(this::cacheOrMap).list());
    }

    @Override
    public long findProcessDefinitionCountByQueryCriteria(ProcessDefinitionQueryImpl query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM process_definition");
        Map<String, Object> params = whereClause(query, sql);
        var spec = jdbcClient().sql(sql.toString());
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query(Long.class).single();
    }

    private Map<String, Object> whereClause(ProcessDefinitionQueryImpl query, StringBuilder sql) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
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
            params.put(PARAM_DEPLOYMENT_ID, query.getDeploymentId());
        }
        if (query.getKey() != null) {
            conditions.add("process_key = :key");
            params.put("key", query.getKey());
        }
        if (query.getKeyLike() != null) {
            conditions.add("process_key LIKE :keyLike");
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
            params.put(PARAM_VERSION, query.getVersion());
        }
        if (query.getVersionGt() != null) {
            conditions.add("version > :versionGt");
            params.put("versionGt", query.getVersionGt());
        }
        if (query.getVersionGte() != null) {
            conditions.add("version >= :versionGte");
            params.put("versionGte", query.getVersionGte());
        }
        if (query.getVersionLt() != null) {
            conditions.add("version < :versionLt");
            params.put("versionLt", query.getVersionLt());
        }
        if (query.getVersionLte() != null) {
            conditions.add("version <= :versionLte");
            params.put("versionLte", query.getVersionLte());
        }
        if (query.isLatest()) {
            conditions.add("version = (SELECT MAX(pd2.version) FROM process_definition pd2 WHERE pd2.process_key = process_definition.process_key)");
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        return params;
    }

    @Override
    public ProcessDefinitionEntity findProcessDefinitionByDeploymentAndKey(String deploymentId, String key) {
        return jdbcClient().sql("""
                SELECT id, deployment_id, process_key, name, version, resource_name, revision
                FROM process_definition WHERE deployment_id = :deploymentId AND process_key = :key
                """)
                .param(PARAM_DEPLOYMENT_ID, deploymentId)
                .param("key", key)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public ProcessDefinitionEntity findProcessDefinitionByDeploymentAndKeyAndTenantId(String deploymentId, String key, String tenantId) {
        return findProcessDefinitionByDeploymentAndKey(deploymentId, key);
    }

    @Override
    public ProcessDefinitionEntity findProcessDefinitionByParentDeploymentAndKey(String parentDeploymentId, String key) {
        return null;
    }

    @Override
    public ProcessDefinitionEntity findProcessDefinitionByParentDeploymentAndKeyAndTenantId(String parentDeploymentId, String key, String tenantId) {
        return null;
    }

    @Override
    public ProcessDefinitionEntity findProcessDefinitionByKeyAndVersion(String key, Integer version) {
        return jdbcClient().sql("""
                SELECT id, deployment_id, process_key, name, version, resource_name, revision
                FROM process_definition WHERE process_key = :key AND version = :version
                """)
                .param("key", key)
                .param(PARAM_VERSION, version)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public ProcessDefinitionEntity findProcessDefinitionByKeyAndVersionAndTenantId(String key, Integer version, String tenantId) {
        return findProcessDefinitionByKeyAndVersion(key, version);
    }

    @Override
    public List<ProcessDefinition> findProcessDefinitionsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findProcessDefinitionCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    @Override
    public void updateProcessDefinitionTenantIdForDeployment(String deploymentId, String newTenantId) {
        // no-op: tenants aren't modeled in this proof.
    }

    @Override
    public void updateProcessDefinitionVersionForProcessDefinitionId(String processDefinitionId, int version) {
        jdbcClient().sql("UPDATE process_definition SET version = :version WHERE id = :id")
                .param("id", processDefinitionId)
                .param(PARAM_VERSION, version)
                .update();
    }

    private ProcessDefinitionEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        ProcessDefinitionEntity cached = session().getCached(ProcessDefinitionEntity.class, id);
        if (cached != null) {
            return cached;
        }
        ProcessDefinitionEntity mapped = mapRow(rs, rowNum);
        session().cache(ProcessDefinitionEntity.class, id, mapped);
        return mapped;
    }

    private ProcessDefinitionEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        ProcessDefinitionEntityImpl entity = new ProcessDefinitionEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setDeploymentId(rs.getString("deployment_id"));
        entity.setKey(rs.getString("process_key"));
        entity.setName(rs.getString("name"));
        entity.setVersion(rs.getInt(PARAM_VERSION));
        entity.setResourceName(rs.getString("resource_name"));
        entity.setRevision(rs.getInt(COL_REVISION));
        return entity;
    }
}
