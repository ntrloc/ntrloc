package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.impl.DeploymentQueryImpl;
import org.flowable.engine.impl.persistence.entity.DeploymentEntity;
import org.flowable.engine.impl.persistence.entity.DeploymentEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.DeploymentDataManager;
import org.flowable.engine.repository.Deployment;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Backs org.flowable.engine.repository.Deployment with our own process_deployment table instead
// of Flowable's ACT_RE_DEPLOYMENT. Query-criteria/native-query finder methods are stubbed with
// empty results -- unused by the trivial single-deployment hello-world process; needed before
// this supports the RepositoryService query API or multiple concurrent deployments.
public class DeploymentDataManagerImpl extends AbstractProcessDataManager implements DeploymentDataManager {

    @Override
    public DeploymentEntity create() {
        return new DeploymentEntityImpl();
    }

    @Override
    public DeploymentEntity findById(String id) {
        return jdbcClient().sql("SELECT id, name, deployment_time FROM process_deployment WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(DeploymentEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("INSERT INTO process_deployment (id, name, deployment_time) VALUES (:id, :name, :time)")
                .param("id", entity.getId())
                .param("name", entity.getName())
                .param("time", entity.getDeploymentTime() == null ? null : new Timestamp(entity.getDeploymentTime().getTime()))
                .update();
        session().cache(DeploymentEntity.class, entity.getId(), entity);
    }

    @Override
    public DeploymentEntity update(DeploymentEntity entity) {
        jdbcClient().sql("UPDATE process_deployment SET name = :name, deployment_time = :time WHERE id = :id")
                .param("id", entity.getId())
                .param("name", entity.getName())
                .param("time", entity.getDeploymentTime() == null ? null : new Timestamp(entity.getDeploymentTime().getTime()))
                .update();
        session().cache(DeploymentEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_deployment WHERE id = :id").param("id", id).update();
        session().evict(DeploymentEntity.class, id);
    }

    @Override
    public void delete(DeploymentEntity entity) {
        delete(entity.getId());
    }

    @Override
    public long findDeploymentCountByQueryCriteria(DeploymentQueryImpl deploymentQuery) {
        return 0;
    }

    @Override
    public List<Deployment> findDeploymentsByQueryCriteria(DeploymentQueryImpl deploymentQuery) {
        return new ArrayList<>();
    }

    @Override
    public List<String> getDeploymentResourceNames(String deploymentId) {
        return jdbcClient().sql("SELECT name FROM process_resource WHERE deployment_id = :id")
                .param("id", deploymentId)
                .query(String.class)
                .list();
    }

    @Override
    public List<Deployment> findDeploymentsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findDeploymentCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    private DeploymentEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        DeploymentEntity cached = session().getCached(DeploymentEntity.class, id);
        if (cached != null) {
            return cached;
        }
        DeploymentEntity mapped = mapRow(rs, rowNum);
        session().cache(DeploymentEntity.class, id, mapped);
        return mapped;
    }

    private DeploymentEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        DeploymentEntityImpl entity = new DeploymentEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setName(rs.getString("name"));
        Timestamp time = rs.getTimestamp("deployment_time");
        entity.setDeploymentTime(time == null ? null : new java.util.Date(time.getTime()));
        return entity;
    }
}
