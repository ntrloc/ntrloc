package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.identitylink.api.history.HistoricIdentityLink;
import org.flowable.identitylink.service.impl.persistence.entity.IdentityLinkEntity;
import org.flowable.identitylink.service.impl.persistence.entity.IdentityLinkEntityImpl;
import org.flowable.identitylink.service.impl.persistence.entity.data.IdentityLinkDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// Backs candidate/assignee/participant identity links with our own process_task_identitylink
// table -- the same JdbcClient-backed pattern as every other custom DataManager in this package.
// Task-scoped links (type "candidate", "assignee") and process-instance-scoped links (type
// "participant", written whenever a user claims/completes a task -- confirmed empirically, not
// something this app deliberately uses) share the same table and DataManager, mirroring how
// Flowable's own IdentityLinkEntity itself carries both a taskId and a processInstanceId field
// and leaves whichever doesn't apply null. Only what this app actually reads back is implemented
// for real (findIdentityLinksByTaskId, findIdentityLinkByTaskUserGroupAndType,
// deleteIdentityLinksByTaskId, deleteIdentityLinksByProcessInstanceId, base CRUD); scope/process-
// definition-level variants stub to empty/no-op, matching TaskDataManagerImpl's own precedent --
// this app has no CMMN usage or process-definition-level candidate tracking.
public class IdentityLinkDataManagerImpl extends AbstractProcessDataManager implements IdentityLinkDataManager {

    @Override
    public IdentityLinkEntity create() {
        return new IdentityLinkEntityImpl();
    }

    @Override
    public IdentityLinkEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(IdentityLinkEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_task_identitylink (id, task_id, process_instance_id, type, user_id, group_id)
                VALUES (:id, :taskId, :processInstanceId, :type, :userId, :groupId)
                """)
                .param("id", entity.getId())
                .param("taskId", entity.getTaskId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("type", entity.getType())
                .param("userId", entity.getUserId())
                .param("groupId", entity.getGroupId())
                .update();
        session().cache(IdentityLinkEntity.class, entity.getId(), entity);
    }

    @Override
    public IdentityLinkEntity update(IdentityLinkEntity entity) {
        jdbcClient().sql("""
                UPDATE process_task_identitylink SET
                    task_id = :taskId, process_instance_id = :processInstanceId, type = :type,
                    user_id = :userId, group_id = :groupId
                WHERE id = :id
                """)
                .param("id", entity.getId())
                .param("taskId", entity.getTaskId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("type", entity.getType())
                .param("userId", entity.getUserId())
                .param("groupId", entity.getGroupId())
                .update();
        session().cache(IdentityLinkEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_task_identitylink WHERE id = :id").param("id", id).update();
        session().evict(IdentityLinkEntity.class, id);
    }

    @Override
    public void delete(IdentityLinkEntity entity) {
        delete(entity.getId());
    }

    // Never actually invoked in practice -- history is off (ProcessEngineConfig), so nothing ever
    // asks to rehydrate an identity link from a historic one. Implemented for real anyway since
    // it's a trivial field copy, rather than throwing and hoping the assumption never breaks.
    @Override
    public IdentityLinkEntity createIdentityLinkFromHistoricIdentityLink(HistoricIdentityLink historicIdentityLink) {
        IdentityLinkEntityImpl entity = new IdentityLinkEntityImpl();
        entity.setTaskId(historicIdentityLink.getTaskId());
        entity.setType(historicIdentityLink.getType());
        entity.setUserId(historicIdentityLink.getUserId());
        entity.setGroupId(historicIdentityLink.getGroupId());
        entity.setProcessInstanceId(historicIdentityLink.getProcessInstanceId());
        return entity;
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinksByTaskId(String taskId) {
        return jdbcClient().sql(SELECT + " WHERE task_id = :id")
                .param("id", taskId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinksByProcessInstanceId(String processInstanceId) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinksByProcessDefinitionId(String processDefinitionId) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinksByScopeIdAndType(String scopeId, String scopeType) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinksBySubScopeIdAndType(String subScopeId, String scopeType) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinksByScopeDefinitionIdAndType(String scopeDefinitionId, String scopeType) {
        return new ArrayList<>();
    }

    // Flowable calls this with whichever of userId/groupId/type are actually relevant to the check
    // it's making (e.g. "does this exact candidate-user link already exist") -- null means "don't
    // filter on this column", matching how the rest of this codebase's finders treat optional
    // criteria.
    @Override
    public List<IdentityLinkEntity> findIdentityLinkByTaskUserGroupAndType(String taskId, String userId, String groupId, String type) {
        String sql = SELECT + " WHERE task_id = :taskId"
                + (userId != null ? " AND user_id = :userId" : "")
                + (groupId != null ? " AND group_id = :groupId" : "")
                + (type != null ? " AND type = :type" : "");
        var spec = jdbcClient().sql(sql).param("taskId", taskId);
        if (userId != null) spec = spec.param("userId", userId);
        if (groupId != null) spec = spec.param("groupId", groupId);
        if (type != null) spec = spec.param("type", type);
        return spec.query(this::cacheOrMap).list();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinkByProcessInstanceUserGroupAndType(String processInstanceId, String userId, String groupId, String type) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinkByProcessDefinitionUserAndGroup(String processDefinitionId, String userId, String groupId) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinkByScopeIdScopeTypeUserGroupAndType(String scopeId, String scopeType, String userId, String groupId, String type) {
        return new ArrayList<>();
    }

    @Override
    public List<IdentityLinkEntity> findIdentityLinkByScopeDefinitionScopeTypeUserAndGroup(String scopeDefinitionId, String scopeType, String userId, String groupId) {
        return new ArrayList<>();
    }

    @Override
    public void deleteIdentityLinksByTaskId(String taskId) {
        jdbcClient().sql("DELETE FROM process_task_identitylink WHERE task_id = :id")
                .param("id", taskId)
                .update();
    }

    @Override
    public void deleteIdentityLinksByProcDef(String processDefId) {
        // no-op: no process-definition-level candidate links in this app.
    }

    // Real, not stubbed: this is where "participant" links (see the class comment) actually get
    // cleaned up when their process instance ends -- otherwise they'd accumulate forever, unlike
    // every other entity in this app which returns to zero rows once a process completes.
    @Override
    public void deleteIdentityLinksByProcessInstanceId(String processInstanceId) {
        jdbcClient().sql("DELETE FROM process_task_identitylink WHERE process_instance_id = :id")
                .param("id", processInstanceId)
                .update();
    }

    @Override
    public void deleteIdentityLinksByScopeIdAndScopeType(String scopeId, String scopeType) {
        // no-op: CMMN scopes aren't modeled in this app.
    }

    @Override
    public void deleteIdentityLinksByScopeDefinitionIdAndScopeType(String scopeDefinitionId, String scopeType) {
        // no-op, see deleteIdentityLinksByScopeIdAndScopeType.
    }

    @Override
    public void bulkDeleteIdentityLinksForScopeIdsAndScopeType(Collection<String> scopeIds, String scopeType) {
        // no-op, see deleteIdentityLinksByScopeIdAndScopeType.
    }

    private static final String SELECT = """
            SELECT id, task_id, process_instance_id, type, user_id, group_id
            FROM process_task_identitylink
            """;

    private IdentityLinkEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        IdentityLinkEntity cached = session().getCached(IdentityLinkEntity.class, id);
        if (cached != null) {
            return cached;
        }
        IdentityLinkEntity mapped = mapRow(rs, rowNum);
        session().cache(IdentityLinkEntity.class, id, mapped);
        return mapped;
    }

    private IdentityLinkEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        IdentityLinkEntityImpl entity = new IdentityLinkEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setTaskId(rs.getString("task_id"));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setType(rs.getString("type"));
        entity.setUserId(rs.getString("user_id"));
        entity.setGroupId(rs.getString("group_id"));
        return entity;
    }
}
