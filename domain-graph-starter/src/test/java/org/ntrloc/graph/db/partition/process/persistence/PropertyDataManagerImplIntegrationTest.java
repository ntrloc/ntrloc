package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.persistence.entity.PropertyEntity;
import org.flowable.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers PropertyDataManagerImpl's CRUD/finder logic directly, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(). A property's id *is* its name (see the class's own comment
// on why setId() is never called), so insert() here always goes through setName() only.
// process_property is a shared table with no per-row test-scoping column, so every test gives its
// own rows a UUID-suffixed name and filters on that, never a shared literal.
class PropertyDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final PropertyDataManagerImpl dataManager = new PropertyDataManagerImpl();

    private String insert(String name, String value) {
        return managementService.executeCommand(cc -> {
            PropertyEntity entity = dataManager.create();
            entity.setName(name);
            entity.setValue(value);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoNameSet() {
        PropertyEntity entity = dataManager.create();
        assertThat(entity.getName()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity_idIsTheName() {
        String name = "prop-" + UUID.randomUUID();
        String id = insert(name, "some-value");

        assertThat(id).isEqualTo(name);
        PropertyEntity found = managementService.executeCommand(cc -> dataManager.findById(name));
        assertThat(found.getValue()).isEqualTo("some-value");
        assertThat(found.getRevision()).isEqualTo(1);
    }

    @Test
    void findById_forUnknownName_returnsNull() {
        PropertyEntity found = managementService.executeCommand(
                cc -> dataManager.findById("prop-" + UUID.randomUUID()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String name = "prop-" + UUID.randomUUID();
        insert(name, "some-value");

        Boolean sameInstance = managementService.executeCommand(cc -> {
            PropertyEntity first = dataManager.findById(name);
            PropertyEntity second = dataManager.findById(name);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChangesAndIncrementsRevision() {
        String name = "prop-" + UUID.randomUUID();
        insert(name, "original-value");

        managementService.executeCommand(cc -> {
            PropertyEntity entity = dataManager.findById(name);
            entity.setValue("updated-value");
            return dataManager.update(entity);
        });

        PropertyEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(name));
        assertThat(reloaded.getValue()).isEqualTo("updated-value");
        assertThat(reloaded.getRevision()).isEqualTo(2);
    }

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String name = "prop-" + UUID.randomUUID();
        insert(name, "original-value");

        PropertyEntity firstReader = managementService.executeCommand(cc -> dataManager.findById(name));
        PropertyEntity secondReader = managementService.executeCommand(cc -> dataManager.findById(name));

        managementService.executeCommand(cc -> {
            firstReader.setValue("first-write");
            return dataManager.update(firstReader);
        });

        assertThatThrownBy(() -> managementService.executeCommand(cc -> {
            secondReader.setValue("second-write");
            return dataManager.update(secondReader);
        })).isInstanceOf(FlowableOptimisticLockingException.class);
    }

    @Test
    void delete_removesTheRow() {
        String name = "prop-" + UUID.randomUUID();
        insert(name, "some-value");

        managementService.executeCommand(cc -> {
            dataManager.delete(name);
            return null;
        });

        PropertyEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(name));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String name = "prop-" + UUID.randomUUID();
        insert(name, "some-value");

        managementService.executeCommand(cc -> {
            PropertyEntity entity = dataManager.findById(name);
            dataManager.delete(entity);
            return null;
        });

        PropertyEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(name));
        assertThat(afterDelete).isNull();
    }

    @Test
    void findAll_includesEveryInsertedProperty() {
        String name1 = "prop-" + UUID.randomUUID();
        String name2 = "prop-" + UUID.randomUUID();
        insert(name1, "value-1");
        insert(name2, "value-2");

        List<PropertyEntity> all = managementService.executeCommand(cc -> dataManager.findAll());

        assertThat(all).extracting(PropertyEntity::getName).contains(name1, name2);
    }

    @Test
    void directInsertProperty_bypassesTheEntityCacheButIsStillFindable() {
        String name = "prop-" + UUID.randomUUID();

        managementService.executeCommand(cc -> {
            dataManager.directInsertProperty(name, "direct-value");
            return null;
        });

        PropertyEntity found = managementService.executeCommand(cc -> dataManager.findById(name));
        assertThat(found.getValue()).isEqualTo("direct-value");
    }
}
