package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.ManagementService;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.eventsubscription.service.impl.persistence.entity.CompensateEventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.EventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.MessageEventSubscriptionEntity;
import org.flowable.eventsubscription.service.impl.persistence.entity.SignalEventSubscriptionEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers EventSubscriptionDataManagerImpl's CRUD/finder logic directly, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(). process_event_subscription is a shared table with no
// per-row test-scoping column, so every test gives its own rows a UUID-suffixed event
// name/execution id/process definition id and filters on that, never a shared literal, staying
// isolated from whatever other test methods have left in the same singleton Postgres container
// (see AbstractIntegrationTest's own comment on why the container isn't per-class).
class EventSubscriptionDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final EventSubscriptionDataManagerImpl dataManager = new EventSubscriptionDataManagerImpl();

    private String insertMessageSubscription(String eventName, String executionId, String processInstanceId,
                                              String processDefinitionId) {
        return managementService.executeCommand(cc -> {
            MessageEventSubscriptionEntity entity = dataManager.createMessageEventSubscription();
            entity.setEventName(eventName);
            entity.setExecutionId(executionId);
            entity.setProcessInstanceId(processInstanceId);
            entity.setProcessDefinitionId(processDefinitionId);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    private String insertSignalSubscription(String eventName, String executionId) {
        return managementService.executeCommand(cc -> {
            SignalEventSubscriptionEntity entity = dataManager.createSignalEventSubscription();
            entity.setEventName(eventName);
            entity.setExecutionId(executionId);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- create() variants ---

    @Test
    void create_returnsAGenericSubscriptionWithNoEventTypeSet() {
        EventSubscriptionEntity entity = dataManager.create();
        assertThat(entity.getEventType()).isNull();
    }

    @Test
    void createMessageEventSubscription_setsTheMessageEventType() {
        assertThat(dataManager.createMessageEventSubscription().getEventType())
                .isEqualTo(MessageEventSubscriptionEntity.EVENT_TYPE);
    }

    @Test
    void createSignalEventSubscription_setsTheSignalEventType() {
        assertThat(dataManager.createSignalEventSubscription().getEventType())
                .isEqualTo(SignalEventSubscriptionEntity.EVENT_TYPE);
    }

    @Test
    void createCompensateEventSubscription_setsTheCompensateEventType() {
        assertThat(dataManager.createCompensateEventSubscription().getEventType())
                .isEqualTo(CompensateEventSubscriptionEntity.EVENT_TYPE);
    }

    @Test
    void createGenericEventSubscriptionEntity_setsNoEventType() {
        assertThat(dataManager.createGenericEventSubscriptionEntity().getEventType()).isNull();
    }

    // --- Basic CRUD ---

    @Test
    void insertThenFindById_returnsAMatchingEntity_andDispatchesToTheRightSubtypeOnReload() {
        String eventName = "event-" + UUID.randomUUID();
        String id = insertMessageSubscription(eventName, null, null, null);

        EventSubscriptionEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found).isInstanceOf(MessageEventSubscriptionEntity.class);
        assertThat(found.getEventName()).isEqualTo(eventName);
        assertThat(found.getCreated()).isNotNull();
    }

    @Test
    void insertASignalSubscriptionThenFindById_dispatchesToSignalSubtype() {
        String id = insertSignalSubscription("event-" + UUID.randomUUID(), null);

        EventSubscriptionEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found).isInstanceOf(SignalEventSubscriptionEntity.class);
    }

    @Test
    void insertACompensateSubscriptionThenFindById_dispatchesToCompensateSubtype() {
        String id = managementService.executeCommand(cc -> {
            CompensateEventSubscriptionEntity entity = dataManager.createCompensateEventSubscription();
            entity.setEventName("event-" + UUID.randomUUID());
            dataManager.insert(entity);
            return entity.getId();
        });

        EventSubscriptionEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found).isInstanceOf(CompensateEventSubscriptionEntity.class);
    }

    @Test
    void insertAGenericSubscriptionThenFindById_dispatchesToTheGenericDefault() {
        String id = managementService.executeCommand(cc -> {
            EventSubscriptionEntity entity = dataManager.createGenericEventSubscriptionEntity();
            entity.setEventType("some-unrecognized-type");
            entity.setEventName("event-" + UUID.randomUUID());
            dataManager.insert(entity);
            return entity.getId();
        });

        EventSubscriptionEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getEventType()).isEqualTo("some-unrecognized-type");
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        EventSubscriptionEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, null);

        Boolean sameInstance = managementService.executeCommand(cc -> {
            EventSubscriptionEntity first = dataManager.findById(id);
            EventSubscriptionEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChangesAndIncrementsRevision() {
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, null);

        managementService.executeCommand(cc -> {
            EventSubscriptionEntity entity = dataManager.findById(id);
            entity.setConfiguration("updated-config");
            return dataManager.update(entity);
        });

        EventSubscriptionEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getConfiguration()).isEqualTo("updated-config");
        assertThat(reloaded.getRevision()).isEqualTo(2);
    }

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, null);

        EventSubscriptionEntity firstReader = managementService.executeCommand(cc -> dataManager.findById(id));
        EventSubscriptionEntity secondReader = managementService.executeCommand(cc -> dataManager.findById(id));

        managementService.executeCommand(cc -> {
            firstReader.setConfiguration("first-write");
            return dataManager.update(firstReader);
        });

        assertThatThrownBy(() -> managementService.executeCommand(cc -> {
            secondReader.setConfiguration("second-write");
            return dataManager.update(secondReader);
        })).isInstanceOf(FlowableOptimisticLockingException.class);
    }

    @Test
    void delete_removesTheRow() {
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, null);

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        EventSubscriptionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, null);

        managementService.executeCommand(cc -> {
            EventSubscriptionEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        EventSubscriptionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Finders ---

    @Test
    void findEventSubscriptionsByName_matchesOnTypeAndName() {
        String eventName = "event-" + UUID.randomUUID();
        String id = insertMessageSubscription(eventName, null, null, null);

        List<EventSubscriptionEntity> found = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByName(MessageEventSubscriptionEntity.EVENT_TYPE, eventName, null));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactly(id);
    }

    @Test
    void findEventSubscriptionsByTypeAndProcessDefinitionId_matchesOnBoth() {
        String processDefinitionId = "procdef-" + UUID.randomUUID();
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, processDefinitionId);

        List<EventSubscriptionEntity> found = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByTypeAndProcessDefinitionId(
                        MessageEventSubscriptionEntity.EVENT_TYPE, processDefinitionId, null));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactly(id);
    }

    @Test
    void findMessageStartEventSubscriptionByName_findsAStartEventSubscription() {
        String eventName = "event-" + UUID.randomUUID();
        String id = insertMessageSubscription(eventName, null, null, null);

        MessageEventSubscriptionEntity found = managementService.executeCommand(
                cc -> dataManager.findMessageStartEventSubscriptionByName(eventName, null));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findMessageStartEventSubscriptionByName_ignoresNonStartSubscriptions_scopedToAnExecution() {
        String eventName = "event-" + UUID.randomUUID();
        insertMessageSubscription(eventName, "exec-" + UUID.randomUUID(), "proc-" + UUID.randomUUID(), null);

        MessageEventSubscriptionEntity found = managementService.executeCommand(
                cc -> dataManager.findMessageStartEventSubscriptionByName(eventName, null));

        assertThat(found).isNull();
    }

    @Test
    void findSignalEventSubscriptionsByEventName_returnsEveryMatch() {
        String eventName = "event-" + UUID.randomUUID();
        String id1 = insertSignalSubscription(eventName, null);
        String id2 = insertSignalSubscription(eventName, null);

        List<SignalEventSubscriptionEntity> found = managementService.executeCommand(
                cc -> dataManager.findSignalEventSubscriptionsByEventName(eventName, null));

        assertThat(found).extracting(SignalEventSubscriptionEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void findSignalEventSubscriptionsByNameAndExecution_isScopedToOneExecution() {
        String eventName = "event-" + UUID.randomUUID();
        String executionId = "exec-" + UUID.randomUUID();
        String id = insertSignalSubscription(eventName, executionId);
        insertSignalSubscription(eventName, "exec-" + UUID.randomUUID());

        List<SignalEventSubscriptionEntity> found = managementService.executeCommand(
                cc -> dataManager.findSignalEventSubscriptionsByNameAndExecution(eventName, executionId));

        assertThat(found).extracting(SignalEventSubscriptionEntity::getId).containsExactly(id);
    }

    @Test
    void findEventSubscriptionsByExecution_returnsEveryTypeForThatExecution() {
        String executionId = "exec-" + UUID.randomUUID();
        String messageId = insertMessageSubscription("event-" + UUID.randomUUID(), executionId, "proc-1", null);
        String signalId = insertSignalSubscription("event-" + UUID.randomUUID(), executionId);

        List<EventSubscriptionEntity> found = managementService.executeCommand(
                cc -> dataManager.findEventSubscriptionsByExecution(executionId));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactlyInAnyOrder(messageId, signalId);
    }

    @Test
    void findEventSubscriptionsByExecutionAndType_isScopedToBoth() {
        String executionId = "exec-" + UUID.randomUUID();
        String messageId = insertMessageSubscription("event-" + UUID.randomUUID(), executionId, "proc-1", null);
        insertSignalSubscription("event-" + UUID.randomUUID(), executionId);

        List<EventSubscriptionEntity> found = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByExecutionAndType(executionId, MessageEventSubscriptionEntity.EVENT_TYPE));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactly(messageId);
    }

    @Test
    void findEventSubscriptionsByProcessInstanceAndType_isScopedToBoth() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), "exec-1", processInstanceId, null);

        List<EventSubscriptionEntity> found = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByProcessInstanceAndType(
                        processInstanceId, MessageEventSubscriptionEntity.EVENT_TYPE));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactly(id);
    }

    @Test
    void findEventSubscriptionsByProcessInstanceAndActivityId_isScopedToAllThree() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String id = managementService.executeCommand(cc -> {
            MessageEventSubscriptionEntity entity = dataManager.createMessageEventSubscription();
            entity.setEventName("event-" + UUID.randomUUID());
            entity.setProcessInstanceId(processInstanceId);
            entity.setActivityId("catch-message");
            dataManager.insert(entity);
            return entity.getId();
        });

        List<EventSubscriptionEntity> found = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByProcessInstanceAndActivityId(
                        processInstanceId, "catch-message", MessageEventSubscriptionEntity.EVENT_TYPE));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactly(id);
    }

    @Test
    void findEventSubscriptionsByNameAndExecution_isScopedToAllThree() {
        String eventName = "event-" + UUID.randomUUID();
        String executionId = "exec-" + UUID.randomUUID();
        String id = insertMessageSubscription(eventName, executionId, "proc-1", null);

        List<EventSubscriptionEntity> found = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByNameAndExecution(
                        MessageEventSubscriptionEntity.EVENT_TYPE, eventName, executionId));

        assertThat(found).extracting(EventSubscriptionEntity::getId).containsExactly(id);
    }

    @Test
    void updateEventSubscriptionProcessDefinitionId_repointsMatchingSubscriptions() {
        String oldProcessDefinitionId = "procdef-old-" + UUID.randomUUID();
        String newProcessDefinitionId = "procdef-new-" + UUID.randomUUID();
        String eventName = "event-" + UUID.randomUUID();
        String id = insertMessageSubscription(eventName, null, null, oldProcessDefinitionId);

        managementService.executeCommand(cc -> {
            dataManager.updateEventSubscriptionProcessDefinitionId(oldProcessDefinitionId, newProcessDefinitionId,
                    MessageEventSubscriptionEntity.EVENT_TYPE, eventName, null, null);
            return null;
        });

        EventSubscriptionEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getProcessDefinitionId()).isEqualTo(newProcessDefinitionId);
    }

    @Test
    void deleteEventSubscriptionsForProcessDefinition_onlyRemovesStartEventSubscriptions() {
        String processDefinitionId = "procdef-" + UUID.randomUUID();
        String startEventId = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, processDefinitionId);
        String runningInstanceEventId = insertMessageSubscription(
                "event-" + UUID.randomUUID(), "exec-1", "proc-1", processDefinitionId);

        managementService.executeCommand(cc -> {
            dataManager.deleteEventSubscriptionsForProcessDefinition(processDefinitionId);
            return null;
        });

        EventSubscriptionEntity startEventAfterDelete = managementService.executeCommand(cc -> dataManager.findById(startEventId));
        EventSubscriptionEntity runningInstanceEventAfterDelete = managementService.executeCommand(cc -> dataManager.findById(runningInstanceEventId));
        assertThat(startEventAfterDelete).isNull();
        assertThat(runningInstanceEventAfterDelete).isNotNull();
    }

    @Test
    void deleteEventSubscriptionsByExecutionId_removesEverySubscriptionForThatExecution() {
        String executionId = "exec-" + UUID.randomUUID();
        String id = insertMessageSubscription("event-" + UUID.randomUUID(), executionId, "proc-1", null);

        managementService.executeCommand(cc -> {
            dataManager.deleteEventSubscriptionsByExecutionId(executionId);
            return null;
        });

        EventSubscriptionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteEventSubscriptionsForProcessDefinitionAndProcessStartEvent_isScopedToAllFour() {
        String processDefinitionId = "procdef-" + UUID.randomUUID();
        String eventName = "event-" + UUID.randomUUID();
        String matchingId = insertMessageSubscription(eventName, null, null, processDefinitionId);
        String differentNameId = insertMessageSubscription("event-" + UUID.randomUUID(), null, null, processDefinitionId);

        managementService.executeCommand(cc -> {
            dataManager.deleteEventSubscriptionsForProcessDefinitionAndProcessStartEvent(
                    processDefinitionId, MessageEventSubscriptionEntity.EVENT_TYPE, eventName, null);
            return null;
        });

        EventSubscriptionEntity matchingAfterDelete = managementService.executeCommand(cc -> dataManager.findById(matchingId));
        EventSubscriptionEntity differentNameAfterDelete = managementService.executeCommand(cc -> dataManager.findById(differentNameId));
        assertThat(matchingAfterDelete).isNull();
        assertThat(differentNameAfterDelete).isNotNull();
    }

    @Test
    void updateEventSubscriptionTenantId_isANoOpAndDoesNotThrow_tenantsArentModeledInThisApp() {
        managementService.executeCommand(cc -> {
            dataManager.updateEventSubscriptionTenantId("old-tenant", "new-tenant");
            return null;
        });
    }

    // --- CMMN-scope / lock-time / query-object surfaces: all stubbed, matching this package's
    //     convention -- nothing in this app (BPMN message/signal start events only) exercises them ---

    @Test
    void cmmnScopeFinders_areAllStubbedEmpty() {
        List<SignalEventSubscriptionEntity> byProcessInstanceAndEventName = managementService.executeCommand(cc -> dataManager
                .findSignalEventSubscriptionsByProcessInstanceAndEventName("proc-1", "event-1"));
        List<SignalEventSubscriptionEntity> byScopeAndEventName = managementService.executeCommand(cc -> dataManager
                .findSignalEventSubscriptionsByScopeAndEventName("scope-1", "scope-type-1", "event-1"));
        List<MessageEventSubscriptionEntity> messageByProcessInstanceAndEventName = managementService.executeCommand(cc -> dataManager
                .findMessageEventSubscriptionsByProcessInstanceAndEventName("proc-1", "event-1"));
        List<EventSubscriptionEntity> bySubScopeId = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsBySubScopeId("sub-scope-1"));
        List<EventSubscriptionEntity> byScopeIdAndType = managementService.executeCommand(cc -> dataManager
                .findEventSubscriptionsByScopeIdAndType("scope-1", "signal"));

        assertThat(byProcessInstanceAndEventName).isEmpty();
        assertThat(byScopeAndEventName).isEmpty();
        assertThat(messageByProcessInstanceAndEventName).isEmpty();
        assertThat(bySubScopeId).isEmpty();
        assertThat(byScopeIdAndType).isEmpty();
    }

    @Test
    void cmmnScopeAndLockTimeMutators_areAllNoOpsAndDoNotThrow() {
        managementService.executeCommand(cc -> {
            dataManager.updateEventSubscriptionScopeDefinitionId("old-scope-def", "new-scope-def", "signal", "event-1", null);
            dataManager.clearEventSubscriptionLockTime(UUID.randomUUID().toString());
            dataManager.deleteEventSubscriptionsForScopeIdAndType("scope-1", "signal");
            dataManager.deleteEventSubscriptionsForScopeDefinitionIdAndType("scope-def-1", "signal");
            dataManager.deleteEventSubscriptionsForScopeDefinitionIdAndTypeAndNullScopeId("scope-def-1", "signal");
            dataManager.deleteEventSubscriptionsForScopeDefinitionAndScopeStartEvent("scope-def-1", "signal", "event-1");
            return null;
        });
    }

    @Test
    void updateEventSubscriptionLockTime_returnsFalse_lockTimeIsNotModeledOnThisTable() {
        Boolean result = managementService.executeCommand(cc -> dataManager.updateEventSubscriptionLockTime(
                UUID.randomUUID().toString(), new java.util.Date(), "owner", new java.util.Date()));
        assertThat(result).isFalse();
    }

    @Test
    void queryObjectSurfaces_areStubbedEmpty_sinceNothingInThisAppUsesThem() {
        List<EventSubscription> results = managementService.executeCommand(
                cc -> dataManager.findEventSubscriptionsByQueryCriteria(null));
        Long count = managementService.executeCommand(cc -> dataManager.findEventSubscriptionCountByQueryCriteria(null));

        assertThat(results).isEmpty();
        assertThat(count).isZero();
    }
}
