package org.nterloc.graph.db.schema;

import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

public class RelationshipDefinition extends SchemaDefinition {

    public enum VersionAction {
        NONE,
        COPY,
        MOVE
    }

    private Set<PropertyGroupDefinition> propertyGroups;

    private Set<PropertyDefinition> properties;

    private String sourceEntity;

    private String targetEntity;

    private Cardinality targetCardinality;

    private Cardinality sourceCardinality;

    private VersionAction sourceVersionAction;

    private VersionAction targetVersionAction;

    private Integer instanceMaxCardinality;

    public Set<PropertyGroupDefinition> getPropertyGroups() {
        return propertyGroups;
    }

    public void setPropertyGroups(Set<PropertyGroupDefinition> propertyGroupDefinitions) {
        this.propertyGroups = propertyGroupDefinitions;
    }

    public Set<PropertyDefinition> getProperties() {
        return properties;
    }

    public void setProperties(Set<PropertyDefinition> properties) {
        this.properties = properties;
    }

    public String getSourceEntity() {
        return sourceEntity;
    }

    public void setSourceEntity(String sourceEntity) {
        this.sourceEntity = sourceEntity;
    }

    public String getTargetEntity() {
        return targetEntity;
    }

    public void setTargetEntity(String targetEntity) {
        this.targetEntity = targetEntity;
    }

    public Cardinality getTargetCardinality() {
        return targetCardinality;
    }

    public void setTargetCardinality(Cardinality targetCardinality) {
        this.targetCardinality = targetCardinality;
    }

    public Cardinality getSourceCardinality() {
        return sourceCardinality;
    }

    public void setSourceCardinality(Cardinality sourceCardinality) {
        this.sourceCardinality = sourceCardinality;
    }

    public VersionAction getSourceVersionAction() {
        return sourceVersionAction;
    }

    public void setSourceVersionAction(VersionAction sourceVersionAction) {
        this.sourceVersionAction = sourceVersionAction;
    }

    public VersionAction getTargetVersionAction() {
        return targetVersionAction;
    }

    public void setTargetVersionAction(VersionAction targetVersionAction) {
        this.targetVersionAction = targetVersionAction;
    }

    public Integer getInstanceMaxCardinality() {
        return instanceMaxCardinality;
    }

    public void setInstanceMaxCardinality(Integer instanceMaxCardinality) {
        this.instanceMaxCardinality = instanceMaxCardinality;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RelationshipDefinition that)) return false;
        if (!super.equals(o)) return false;
        return Objects.equals(propertyGroups, that.propertyGroups)
                && Objects.equals(properties, that.properties)
                && Objects.equals(sourceEntity, that.sourceEntity)
                && Objects.equals(targetEntity, that.targetEntity)
                && Objects.equals(targetCardinality, that.targetCardinality)
                && Objects.equals(sourceCardinality, that.sourceCardinality)
                && sourceVersionAction == that.sourceVersionAction
                && targetVersionAction == that.targetVersionAction
                && Objects.equals(instanceMaxCardinality, that.instanceMaxCardinality);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), propertyGroups, properties, sourceEntity, targetEntity, targetCardinality, sourceCardinality, sourceVersionAction, targetVersionAction, instanceMaxCardinality);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", RelationshipDefinition.class.getSimpleName() + "[", "]")
                .add("propertyGroups=" + propertyGroups)
                .add("properties=" + properties)
                .add("sourceEntity='" + sourceEntity + "'")
                .add("targetEntity='" + targetEntity + "'")
                .add("targetCardinality=" + targetCardinality)
                .add("sourceCardinality=" + sourceCardinality)
                .add("sourceFollowLatest=" + sourceVersionAction)
                .add("targetFollowLatest=" + targetVersionAction)
                .add("instanceCardinality=" + instanceMaxCardinality)
                .add("name='" + name + "'")
                .add("description='" + description + "'")
                .toString();
    }
}
