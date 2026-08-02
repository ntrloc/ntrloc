package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.engine.impl.DmnDeploymentQueryImpl;
import org.flowable.dmn.engine.impl.persistence.entity.DmnDeploymentEntity;
import org.flowable.dmn.engine.impl.persistence.entity.DmnDeploymentEntityImpl;
import org.flowable.dmn.engine.impl.persistence.entity.data.DmnDeploymentDataManager;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Backs org.flowable.dmn.api.DmnDeployment with our own decision_deployment table. Query-
// criteria/native-query finder methods are stubbed with empty results -- nothing in this app lists
// DMN deployments directly, matching DeploymentDataManagerImpl's same stubbing for the process
// engine side.
// java.util.Date is unavoidable here: overrides Flowable's own Date-based entity API.
@SuppressWarnings("java:S2143")
public class DecisionDeploymentDataManagerImpl extends AbstractDecisionDataManager implements DmnDeploymentDataManager {

    @Override
    public DmnDeploymentEntity create() {
        return new DmnDeploymentEntityImpl();
    }

    @Override
    public DmnDeploymentEntity findById(String id) {
        return jdbcClient().sql("SELECT id, name, deployment_time FROM decision_deployment WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(DmnDeploymentEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("INSERT INTO decision_deployment (id, name, deployment_time) VALUES (:id, :name, :time)")
                .param("id", entity.getId())
                .param("name", entity.getName())
                .param("time", entity.getDeploymentTime() == null ? null : new Timestamp(entity.getDeploymentTime().getTime()))
                .update();
        session().cache(DmnDeploymentEntity.class, entity.getId(), entity);
    }

    @Override
    public DmnDeploymentEntity update(DmnDeploymentEntity entity) {
        jdbcClient().sql("UPDATE decision_deployment SET name = :name, deployment_time = :time WHERE id = :id")
                .param("id", entity.getId())
                .param("name", entity.getName())
                .param("time", entity.getDeploymentTime() == null ? null : new Timestamp(entity.getDeploymentTime().getTime()))
                .update();
        session().cache(DmnDeploymentEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM decision_deployment WHERE id = :id").param("id", id).update();
        session().evict(DmnDeploymentEntity.class, id);
    }

    @Override
    public void delete(DmnDeploymentEntity entity) {
        delete(entity.getId());
    }

    @Override
    public long findDeploymentCountByQueryCriteria(DmnDeploymentQueryImpl deploymentQuery) {
        return 0;
    }

    @Override
    public List<DmnDeployment> findDeploymentsByQueryCriteria(DmnDeploymentQueryImpl deploymentQuery) {
        return new ArrayList<>();
    }

    @Override
    public List<String> getDeploymentResourceNames(String deploymentId) {
        return jdbcClient().sql("SELECT name FROM decision_resource WHERE deployment_id = :id")
                .param("id", deploymentId)
                .query(String.class)
                .list();
    }

    @Override
    public List<DmnDeployment> findDeploymentsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findDeploymentCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    private DmnDeploymentEntity cacheOrMap(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String id = rs.getString("id");
        DmnDeploymentEntity cached = session().getCached(DmnDeploymentEntity.class, id);
        if (cached != null) {
            return cached;
        }
        DmnDeploymentEntity mapped = mapRow(rs);
        session().cache(DmnDeploymentEntity.class, id, mapped);
        return mapped;
    }

    private DmnDeploymentEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        DmnDeploymentEntityImpl entity = new DmnDeploymentEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setName(rs.getString("name"));
        Timestamp time = rs.getTimestamp("deployment_time");
        entity.setDeploymentTime(time == null ? null : new java.util.Date(time.getTime()));
        return entity;
    }
}
