package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.eventsubscription.service.impl.EventSubscriptionQueryImpl;
import org.flowable.eventsubscription.service.impl.persistence.entity.CompensateEventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.CompensateEventSubscriptionEntityImpl;
import org.flowable.eventsubscription.service.impl.persistence.entity.EventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.GenericEventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.GenericEventSubscriptionEntityImpl;
import org.flowable.eventsubscription.service.impl.persistence.entity.MessageEventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.MessageEventSubscriptionEntityImpl;
import org.flowable.eventsubscription.service.impl.persistence.entity.SignalEventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.SignalEventSubscriptionEntityImpl;
import org.flowable.eventsubscription.service.impl.persistence.entity.data.EventSubscriptionDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs BPMN message/signal start-event subscriptions (ACT_RU_EVENT_SUBSCR) with our own
// process_event_subscription table -- load-bearing, not cleanup: RuntimeService.
// startProcessInstanceByMessage and signal dispatch 
// depend on this. Only the finder methods actually called by deployment-time subscription
// management (EventSubscriptionManager) and message/signal dispatch are implemented for real --
// compensate events, CMMN-scope variants, and lock-time methods are stubbed, matching this
// package's existing convention (e.g. ExecutionDataManagerImpl). Tenants aren't modeled -- no
// column, tenantId parameters are accepted but ignored, same as every other DataManager here.
public class EventSubscriptionDataManagerImpl extends AbstractProcessDataManager implements EventSubscriptionDataManager {

    @Override
    public EventSubscriptionEntity create() {
        return new GenericEventSubscriptionEntityImpl();
    }

    // The no-arg constructor leaves eventType null -- only the EventSubscriptionServiceConfiguration
    // constructor overload sets it (confirmed via bytecode), and that configuration isn't available
    // here. Set explicitly so insert() doesn't write a NULL into the NOT NULL event_type column.
    @Override
    public MessageEventSubscriptionEntity createMessageEventSubscription() {
        MessageEventSubscriptionEntityImpl entity = new MessageEventSubscriptionEntityImpl();
        entity.setEventType(MessageEventSubscriptionEntity.EVENT_TYPE);
        return entity;
    }

    @Override
    public SignalEventSubscriptionEntity createSignalEventSubscription() {
        SignalEventSubscriptionEntityImpl entity = new SignalEventSubscriptionEntityImpl();
        entity.setEventType(SignalEventSubscriptionEntity.EVENT_TYPE);
        return entity;
    }

    @Override
    public CompensateEventSubscriptionEntity createCompensateEventSubscription() {
        CompensateEventSubscriptionEntityImpl entity = new CompensateEventSubscriptionEntityImpl();
        entity.setEventType(CompensateEventSubscriptionEntity.EVENT_TYPE);
        return entity;
    }

    @Override
    public GenericEventSubscriptionEntity createGenericEventSubscriptionEntity() {
        return new GenericEventSubscriptionEntityImpl();
    }

    @Override
    public EventSubscriptionEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(EventSubscriptionEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_event_subscription
                    (id, revision, event_type, event_name, execution_id, process_instance_id, activity_id,
                     configuration, created, process_definition_id)
                VALUES
                    (:id, :revision, :eventType, :eventName, :executionId, :processInstanceId, :activityId,
                     :configuration, :created, :processDefinitionId)
                """)
                .param("id", entity.getId())
                .param("revision", Math.max(entity.getRevision(), 1))
                .param("eventType", entity.getEventType())
                .param("eventName", entity.getEventName())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("activityId", entity.getActivityId())
                .param("configuration", entity.getConfiguration())
                .param("created", toTimestamp(entity.getCreated() == null ? new Date() : entity.getCreated()))
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .update();
        session().cache(EventSubscriptionEntity.class, entity.getId(), entity);
        session().registerFlush(EventSubscriptionEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public EventSubscriptionEntity update(EventSubscriptionEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_event_subscription SET
                    event_type = :eventType, event_name = :eventName, execution_id = :executionId,
                    process_instance_id = :processInstanceId, activity_id = :activityId,
                    configuration = :configuration, process_definition_id = :processDefinitionId,
                    revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param("revision", entity.getRevision())
                .param("eventType", entity.getEventType())
                .param("eventName", entity.getEventName())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("activityId", entity.getActivityId())
                .param("configuration", entity.getConfiguration())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException(
                    "EventSubscription " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(EventSubscriptionEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_event_subscription WHERE id = :id").param("id", id).update();
        session().evict(EventSubscriptionEntity.class, id);
    }

    @Override
    public void delete(EventSubscriptionEntity entity) {
        delete(entity.getId());
    }

    // Deploy-time uniqueness check for message start events, and the general by-name lookup used
    // when wiring up new subscriptions (the workflow persistence layer
    // insertMessageEvent/insertSignalEvent findings).
    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByName(String eventType, String eventName, String tenantId) {
        return jdbcClient().sql(SELECT + " WHERE event_type = :eventType AND event_name = :eventName")
                .param("eventType", eventType)
                .param("eventName", eventName)
                .query(this::cacheOrMap)
                .list();
    }

    // Obsolete-subscription cleanup on redeploy: every subscription a process definition's
    // *previous* version owned, so EventSubscriptionManager can remove the ones the new version no
    // longer declares.
    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByTypeAndProcessDefinitionId(String eventType, String processDefinitionId, String tenantId) {
        return jdbcClient().sql(SELECT + " WHERE event_type = :eventType AND process_definition_id = :processDefinitionId")
                .param("eventType", eventType)
                .param("processDefinitionId", processDefinitionId)
                .query(this::cacheOrMap)
                .list();
    }

    // Message dispatch: RuntimeService.startProcessInstanceByMessage looks up the single owning
    // start-event subscription by name -- engine-enforced unique per Section 4's findings.
    @Override
    public MessageEventSubscriptionEntity findMessageStartEventSubscriptionByName(String messageName, String tenantId) {
        return jdbcClient().sql(SELECT + " WHERE event_type = 'message' AND event_name = :name AND process_instance_id IS NULL AND execution_id IS NULL")
                .param("name", messageName)
                .query(this::cacheOrMap)
                .optional()
                .map(MessageEventSubscriptionEntity.class::cast)
                .orElse(null);
    }

    // Signal dispatch/fan-out: every process definition's start-event subscription to this signal
    // name -- unlike message, deliberately unbounded (Section 4's N:1 rationale).
    @Override
    public List<SignalEventSubscriptionEntity> findSignalEventSubscriptionsByEventName(String eventName, String tenantId) {
        return jdbcClient().sql(SELECT + " WHERE event_type = 'signal' AND event_name = :name")
                .param("name", eventName)
                .query(this::cacheOrMap)
                .list()
                .stream()
                .map(SignalEventSubscriptionEntity.class::cast)
                .toList();
    }

    // Intermediate signal catch within a running instance -- scoped to one execution, not a
    // start-event fan-out.
    @Override
    public List<SignalEventSubscriptionEntity> findSignalEventSubscriptionsByNameAndExecution(String eventName, String executionId) {
        return jdbcClient().sql(SELECT + " WHERE event_type = 'signal' AND event_name = :name AND execution_id = :executionId")
                .param("name", eventName)
                .param("executionId", executionId)
                .query(this::cacheOrMap)
                .list()
                .stream()
                .map(SignalEventSubscriptionEntity.class::cast)
                .toList();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByExecution(String executionId) {
        return jdbcClient().sql(SELECT + " WHERE execution_id = :id")
                .param("id", executionId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByExecutionAndType(String executionId, String eventType) {
        return jdbcClient().sql(SELECT + " WHERE execution_id = :id AND event_type = :eventType")
                .param("id", executionId)
                .param("eventType", eventType)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByProcessInstanceAndType(String processInstanceId, String eventType) {
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id AND event_type = :eventType")
                .param("id", processInstanceId)
                .param("eventType", eventType)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByProcessInstanceAndActivityId(String processInstanceId, String activityId, String eventType) {
        return jdbcClient().sql(SELECT + " WHERE process_instance_id = :id AND activity_id = :activityId AND event_type = :eventType")
                .param("id", processInstanceId)
                .param("activityId", activityId)
                .param("eventType", eventType)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByNameAndExecution(String eventType, String eventName, String executionId) {
        return jdbcClient().sql(SELECT + " WHERE event_type = :eventType AND event_name = :eventName AND execution_id = :executionId")
                .param("eventType", eventType)
                .param("eventName", eventName)
                .param("executionId", executionId)
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public void updateEventSubscriptionProcessDefinitionId(String oldProcessDefinitionId, String processDefinitionId, String eventType, String eventName, String activityId, String tenantId) {
        jdbcClient().sql("UPDATE process_event_subscription SET process_definition_id = :newId WHERE process_definition_id = :oldId AND event_type = :eventType")
                .param("newId", processDefinitionId)
                .param("oldId", oldProcessDefinitionId)
                .param("eventType", eventType)
                .update();
    }

    @Override
    public void deleteEventSubscriptionsForProcessDefinition(String processDefinitionId) {
        jdbcClient().sql("DELETE FROM process_event_subscription WHERE process_definition_id = :id AND process_instance_id IS NULL")
                .param("id", processDefinitionId)
                .update();
    }

    @Override
    public void deleteEventSubscriptionsByExecutionId(String executionId) {
        jdbcClient().sql("DELETE FROM process_event_subscription WHERE execution_id = :id")
                .param("id", executionId)
                .update();
    }

    @Override
    public void updateEventSubscriptionTenantId(String oldTenantId, String newTenantId) {
        // no-op: tenants aren't modeled in this app.
    }

    // CMMN-scope, lock-time, and query-object/native-query surfaces: stubbed, matching this
    // package's convention -- none of these are exercised by BPMN message/signal start events.
    @Override
    public List<SignalEventSubscriptionEntity> findSignalEventSubscriptionsByProcessInstanceAndEventName(String processInstanceId, String eventName) {
        return new ArrayList<>();
    }

    @Override
    public List<SignalEventSubscriptionEntity> findSignalEventSubscriptionsByScopeAndEventName(String scopeId, String scopeType, String eventName) {
        return new ArrayList<>();
    }

    @Override
    public List<MessageEventSubscriptionEntity> findMessageEventSubscriptionsByProcessInstanceAndEventName(String processInstanceId, String eventName) {
        return new ArrayList<>();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsBySubScopeId(String subScopeId) {
        return new ArrayList<>();
    }

    @Override
    public List<EventSubscriptionEntity> findEventSubscriptionsByScopeIdAndType(String scopeId, String eventType) {
        return new ArrayList<>();
    }

    @Override
    public void updateEventSubscriptionScopeDefinitionId(String oldScopeDefinitionId, String scopeDefinitionId, String eventType, String eventName, String activityId) {
        // no-op: CMMN scoping isn't modeled in this app.
    }

    @Override
    public boolean updateEventSubscriptionLockTime(String id, Date lockDate, String lockOwner, Date expirationTime) {
        return false;
    }

    @Override
    public void clearEventSubscriptionLockTime(String id) {
        // no-op: lock-time isn't modeled on this table (BPMN message/signal start events don't use it).
    }

    @Override
    public void deleteEventSubscriptionsForScopeIdAndType(String scopeId, String eventType) {
        // no-op: CMMN scoping isn't modeled in this app.
    }

    @Override
    public void deleteEventSubscriptionsForScopeDefinitionIdAndType(String scopeDefinitionId, String eventType) {
        // no-op: CMMN scoping isn't modeled in this app.
    }

    @Override
    public void deleteEventSubscriptionsForScopeDefinitionIdAndTypeAndNullScopeId(String scopeDefinitionId, String eventType) {
        // no-op: CMMN scoping isn't modeled in this app.
    }

    @Override
    public void deleteEventSubscriptionsForProcessDefinitionAndProcessStartEvent(String processDefinitionId, String eventType, String eventName, String activityId) {
        jdbcClient().sql("DELETE FROM process_event_subscription WHERE process_definition_id = :id AND event_type = :eventType AND event_name = :eventName AND process_instance_id IS NULL")
                .param("id", processDefinitionId)
                .param("eventType", eventType)
                .param("eventName", eventName)
                .update();
    }

    @Override
    public void deleteEventSubscriptionsForScopeDefinitionAndScopeStartEvent(String scopeDefinitionId, String eventType, String eventName) {
        // no-op: CMMN scoping isn't modeled in this app.
    }

    @Override
    public long findEventSubscriptionCountByQueryCriteria(EventSubscriptionQueryImpl eventSubscriptionQuery) {
        return 0;
    }

    @Override
    public List<EventSubscription> findEventSubscriptionsByQueryCriteria(EventSubscriptionQueryImpl eventSubscriptionQuery) {
        return new ArrayList<>();
    }

    private static final String SELECT = """
            SELECT id, revision, event_type, event_name, execution_id, process_instance_id, activity_id,
                   configuration, created, process_definition_id
            FROM process_event_subscription
            """;

    private EventSubscriptionEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        EventSubscriptionEntity cached = session().getCached(EventSubscriptionEntity.class, id);
        if (cached != null) {
            return cached;
        }
        EventSubscriptionEntity mapped = mapRow(rs);
        session().cache(EventSubscriptionEntity.class, id, mapped);
        session().registerFlush(EventSubscriptionEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private EventSubscriptionEntity mapRow(ResultSet rs) throws SQLException {
        String eventType = rs.getString("event_type");
        EventSubscriptionEntity entity = switch (eventType) {
            case MessageEventSubscriptionEntity.EVENT_TYPE -> new MessageEventSubscriptionEntityImpl();
            case SignalEventSubscriptionEntity.EVENT_TYPE -> new SignalEventSubscriptionEntityImpl();
            case CompensateEventSubscriptionEntity.EVENT_TYPE -> new CompensateEventSubscriptionEntityImpl();
            default -> new GenericEventSubscriptionEntityImpl();
        };
        entity.setId(rs.getString("id"));
        entity.setRevision(rs.getInt("revision"));
        entity.setEventType(eventType);
        entity.setEventName(rs.getString("event_name"));
        entity.setExecutionId(rs.getString("execution_id"));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setActivityId(rs.getString("activity_id"));
        entity.setConfiguration(rs.getString("configuration"));
        entity.setCreated(fromTimestamp(rs.getTimestamp("created")));
        entity.setProcessDefinitionId(rs.getString("process_definition_id"));
        return entity;
    }

    private static Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private static Date fromTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : new Date(timestamp.getTime());
    }
}
