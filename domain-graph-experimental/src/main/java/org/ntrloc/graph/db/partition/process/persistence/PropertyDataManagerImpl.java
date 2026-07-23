package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.persistence.entity.PropertyEntity;
import org.flowable.common.engine.impl.persistence.entity.PropertyEntityImpl;
import org.flowable.common.engine.impl.persistence.entity.data.PropertyDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

// Backs Flowable's generic config-property bookkeeping (ACT_GE_PROPERTY) with our own
// process_property table -- discovered live, not anticipated (docs/ntrloc-workflow-summary.md
// Section 6): ValidateExecutionRelatedEntityCountCfgCmd looks up
// "cfg.execution-related-entities-count" unconditionally during every buildEngine() call, entirely
// independent of schema management or the id generator. A PropertyEntity's own id *is* its name --
// PropertyEntityImpl.setId() throws ("only provided id generation allowed for properties"),
// getId() just returns the name field -- so this never calls assignIdIfMissing() the way every
// other DataManager here does; entity.getId() (== entity.getName()) is always already set by the
// caller before insert().
public class PropertyDataManagerImpl extends AbstractProcessDataManager implements PropertyDataManager {

    @Override
    public PropertyEntity create() {
        return new PropertyEntityImpl();
    }

    @Override
    public PropertyEntity findById(String name) {
        return jdbcClient().sql(SELECT + " WHERE name = :name")
                .param("name", name)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(PropertyEntity entity) {
        jdbcClient().sql("INSERT INTO process_property (name, revision, value) VALUES (:name, :revision, :value)")
                .param("name", entity.getName())
                .param("revision", Math.max(entity.getRevision(), 1))
                .param("value", entity.getValue())
                .update();
        session().cache(PropertyEntity.class, entity.getId(), entity);
        session().registerFlush(PropertyEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public PropertyEntity update(PropertyEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_property SET value = :value, revision = revision + 1
                WHERE name = :name AND revision = :revision
                """)
                .param("name", entity.getName())
                .param("revision", entity.getRevision())
                .param("value", entity.getValue())
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException(
                    "Property " + entity.getName() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(PropertyEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String name) {
        jdbcClient().sql("DELETE FROM process_property WHERE name = :name").param("name", name).update();
        session().evict(PropertyEntity.class, name);
    }

    @Override
    public void delete(PropertyEntity entity) {
        delete(entity.getId());
    }

    @Override
    public List<PropertyEntity> findAll() {
        return jdbcClient().sql(SELECT).query(this::cacheOrMap).list();
    }

    // Used only for the (Flowable-internal) "history.cleaning.timecycle" property seed and similar
    // one-off bootstrap writes elsewhere in the engine -- a plain insert bypassing the entity cache,
    // matching Flowable's own MybatisPropertyDataManager semantics for this method.
    @Override
    public void directInsertProperty(String name, String value) {
        jdbcClient().sql("INSERT INTO process_property (name, value) VALUES (:name, :value)")
                .param("name", name)
                .param("value", value)
                .update();
    }

    private static final String SELECT = "SELECT name, revision, value FROM process_property";

    private PropertyEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String name = rs.getString("name");
        PropertyEntity cached = session().getCached(PropertyEntity.class, name);
        if (cached != null) {
            return cached;
        }
        PropertyEntity mapped = mapRow(rs);
        session().cache(PropertyEntity.class, name, mapped);
        session().registerFlush(PropertyEntity.class, name, mapped, () -> update(mapped));
        return mapped;
    }

    private PropertyEntity mapRow(ResultSet rs) throws SQLException {
        PropertyEntityImpl entity = new PropertyEntityImpl();
        entity.setName(rs.getString("name"));
        entity.setRevision(rs.getInt("revision"));
        entity.setValue(rs.getString("value"));
        return entity;
    }
}
