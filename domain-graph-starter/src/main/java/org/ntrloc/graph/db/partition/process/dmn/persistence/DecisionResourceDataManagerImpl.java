package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.engine.impl.persistence.entity.DmnResourceEntity;
import org.flowable.dmn.engine.impl.persistence.entity.DmnResourceEntityImpl;
import org.flowable.dmn.engine.impl.persistence.entity.data.DmnResourceDataManager;

import java.util.List;

// Backs the raw DMN XML bytes with our own decision_resource table, mirroring
// ResourceDataManagerImpl on the process-engine side exactly.
public class DecisionResourceDataManagerImpl extends AbstractDecisionDataManager implements DmnResourceDataManager {

    private static final String PARAM_DEPLOYMENT_ID = "deploymentId";
    private static final String PARAM_BYTES = "bytes";

    @Override
    public DmnResourceEntity create() {
        return new DmnResourceEntityImpl();
    }

    @Override
    public DmnResourceEntity findById(String id) {
        return jdbcClient().sql("SELECT id, deployment_id, name, bytes FROM decision_resource WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(DmnResourceEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("INSERT INTO decision_resource (id, deployment_id, name, bytes) VALUES (:id, :deploymentId, :name, :bytes)")
                .param("id", entity.getId())
                .param(PARAM_DEPLOYMENT_ID, entity.getDeploymentId())
                .param("name", entity.getName())
                .param(PARAM_BYTES, entity.getBytes())
                .update();
        session().cache(DmnResourceEntity.class, entity.getId(), entity);
    }

    @Override
    public DmnResourceEntity update(DmnResourceEntity entity) {
        jdbcClient().sql("UPDATE decision_resource SET deployment_id = :deploymentId, name = :name, bytes = :bytes WHERE id = :id")
                .param("id", entity.getId())
                .param(PARAM_DEPLOYMENT_ID, entity.getDeploymentId())
                .param("name", entity.getName())
                .param(PARAM_BYTES, entity.getBytes())
                .update();
        session().cache(DmnResourceEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM decision_resource WHERE id = :id").param("id", id).update();
        session().evict(DmnResourceEntity.class, id);
    }

    @Override
    public void delete(DmnResourceEntity entity) {
        delete(entity.getId());
    }

    @Override
    public void deleteResourcesByDeploymentId(String deploymentId) {
        jdbcClient().sql("DELETE FROM decision_resource WHERE deployment_id = :deploymentId")
                .param(PARAM_DEPLOYMENT_ID, deploymentId)
                .update();
    }

    @Override
    public DmnResourceEntity findResourceByDeploymentIdAndResourceName(String deploymentId, String resourceName) {
        return jdbcClient().sql("SELECT id, deployment_id, name, bytes FROM decision_resource WHERE deployment_id = :deploymentId AND name = :name")
                .param(PARAM_DEPLOYMENT_ID, deploymentId)
                .param("name", resourceName)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public List<DmnResourceEntity> findResourcesByDeploymentId(String deploymentId) {
        return jdbcClient().sql("SELECT id, deployment_id, name, bytes FROM decision_resource WHERE deployment_id = :deploymentId")
                .param(PARAM_DEPLOYMENT_ID, deploymentId)
                .query(this::cacheOrMap)
                .list();
    }

    private DmnResourceEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        DmnResourceEntity cached = session().getCached(DmnResourceEntity.class, id);
        if (cached != null) {
            return cached;
        }
        DmnResourceEntity mapped = mapRow(rs, rowNum);
        session().cache(DmnResourceEntity.class, id, mapped);
        return mapped;
    }

    private DmnResourceEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        DmnResourceEntityImpl entity = new DmnResourceEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setDeploymentId(rs.getString("deployment_id"));
        entity.setName(rs.getString("name"));
        entity.setBytes(rs.getBytes(PARAM_BYTES));
        return entity;
    }
}
