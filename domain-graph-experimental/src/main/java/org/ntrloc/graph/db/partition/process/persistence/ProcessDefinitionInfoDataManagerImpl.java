package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionInfoEntity;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionInfoEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ProcessDefinitionInfoDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;

// Backs process-definition "info cache" overrides (ACT_PROCDEF_INFO) with our own
// process_definition_info table -- discovered live (docs/ntrloc-workflow-summary.md Section 6):
// BpmnDeployer.createLocalizationValues() looks this up unconditionally on every deploy, not gated
// behind isEnableProcessDefinitionInfoCache() the way earlier analysis assumed. Nothing in ntrloc
// ever creates an override, so this stays empty in practice -- it just needs to correctly answer
// "no override for this process definition" (null), which findById()/the sole finder both do.
public class ProcessDefinitionInfoDataManagerImpl extends AbstractProcessDataManager implements ProcessDefinitionInfoDataManager {

    @Override
    public ProcessDefinitionInfoEntity create() {
        return new ProcessDefinitionInfoEntityImpl();
    }

    @Override
    public ProcessDefinitionInfoEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public ProcessDefinitionInfoEntity findProcessDefinitionInfoByProcessDefinitionId(String processDefinitionId) {
        return jdbcClient().sql(SELECT + " WHERE process_definition_id = :id")
                .param("id", processDefinitionId)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(ProcessDefinitionInfoEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_definition_info (id, revision, process_definition_id, info_json_id)
                VALUES (:id, :revision, :processDefinitionId, :infoJsonId)
                """)
                .param("id", entity.getId())
                .param("revision", Math.max(entity.getRevision(), 1))
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("infoJsonId", entity.getInfoJsonId())
                .update();
        session().cache(ProcessDefinitionInfoEntity.class, entity.getId(), entity);
        session().registerFlush(ProcessDefinitionInfoEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public ProcessDefinitionInfoEntity update(ProcessDefinitionInfoEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_definition_info SET process_definition_id = :processDefinitionId,
                    info_json_id = :infoJsonId, revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param("revision", entity.getRevision())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("infoJsonId", entity.getInfoJsonId())
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException(
                    "ProcessDefinitionInfo " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(ProcessDefinitionInfoEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_definition_info WHERE id = :id").param("id", id).update();
        session().evict(ProcessDefinitionInfoEntity.class, id);
    }

    @Override
    public void delete(ProcessDefinitionInfoEntity entity) {
        delete(entity.getId());
    }

    private static final String SELECT = "SELECT id, revision, process_definition_id, info_json_id FROM process_definition_info";

    private ProcessDefinitionInfoEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        ProcessDefinitionInfoEntity cached = session().getCached(ProcessDefinitionInfoEntity.class, id);
        if (cached != null) {
            return cached;
        }
        ProcessDefinitionInfoEntity mapped = mapRow(rs);
        session().cache(ProcessDefinitionInfoEntity.class, id, mapped);
        session().registerFlush(ProcessDefinitionInfoEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private ProcessDefinitionInfoEntity mapRow(ResultSet rs) throws SQLException {
        ProcessDefinitionInfoEntityImpl entity = new ProcessDefinitionInfoEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setRevision(rs.getInt("revision"));
        entity.setProcessDefinitionId(rs.getString("process_definition_id"));
        entity.setInfoJsonId(rs.getString("info_json_id"));
        return entity;
    }
}
