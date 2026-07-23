package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.impl.persistence.entity.ResourceEntity;
import org.flowable.engine.impl.persistence.entity.ResourceEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ResourceDataManager;

import java.util.List;

// Backs the raw BPMN/DMN/CMMN XML bytes with our own process_resource table -- the piece that
// ties directly into "direct control over how diagrams are stored" from the workflow design doc.
public class ResourceDataManagerImpl extends AbstractProcessDataManager implements ResourceDataManager {

    @Override
    public ResourceEntity create() {
        return new ResourceEntityImpl();
    }

    @Override
    public ResourceEntity findById(String id) {
        return jdbcClient().sql("SELECT id, deployment_id, name, bytes FROM process_resource WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(ResourceEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("INSERT INTO process_resource (id, deployment_id, name, bytes) VALUES (:id, :deploymentId, :name, :bytes)")
                .param("id", entity.getId())
                .param("deploymentId", entity.getDeploymentId())
                .param("name", entity.getName())
                .param("bytes", entity.getBytes())
                .update();
        session().cache(ResourceEntity.class, entity.getId(), entity);
    }

    @Override
    public ResourceEntity update(ResourceEntity entity) {
        jdbcClient().sql("UPDATE process_resource SET deployment_id = :deploymentId, name = :name, bytes = :bytes WHERE id = :id")
                .param("id", entity.getId())
                .param("deploymentId", entity.getDeploymentId())
                .param("name", entity.getName())
                .param("bytes", entity.getBytes())
                .update();
        session().cache(ResourceEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_resource WHERE id = :id").param("id", id).update();
        session().evict(ResourceEntity.class, id);
    }

    @Override
    public void delete(ResourceEntity entity) {
        delete(entity.getId());
    }

    @Override
    public void deleteResourcesByDeploymentId(String deploymentId) {
        jdbcClient().sql("DELETE FROM process_resource WHERE deployment_id = :deploymentId")
                .param("deploymentId", deploymentId)
                .update();
    }

    @Override
    public ResourceEntity findResourceByDeploymentIdAndResourceName(String deploymentId, String resourceName) {
        return jdbcClient().sql("SELECT id, deployment_id, name, bytes FROM process_resource WHERE deployment_id = :deploymentId AND name = :name")
                .param("deploymentId", deploymentId)
                .param("name", resourceName)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public List<ResourceEntity> findResourcesByDeploymentId(String deploymentId) {
        return jdbcClient().sql("SELECT id, deployment_id, name, bytes FROM process_resource WHERE deployment_id = :deploymentId")
                .param("deploymentId", deploymentId)
                .query(this::cacheOrMap)
                .list();
    }

    private ResourceEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        ResourceEntity cached = session().getCached(ResourceEntity.class, id);
        if (cached != null) {
            return cached;
        }
        ResourceEntity mapped = mapRow(rs, rowNum);
        session().cache(ResourceEntity.class, id, mapped);
        return mapped;
    }

    private ResourceEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        ResourceEntityImpl entity = new ResourceEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setDeploymentId(rs.getString("deployment_id"));
        entity.setName(rs.getString("name"));
        entity.setBytes(rs.getBytes("bytes"));
        return entity;
    }
}
