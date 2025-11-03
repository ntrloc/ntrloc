package org.ntrloc.graph.db.schema;

import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

public class LinkDefinition extends SchemaDefinition implements DefinitionWithPropertyGroups {

    public enum VersionAction {
        NONE,
        COPY,
        MOVE
    }

    private Set<PropertyGroupDefinition> propertyGroups;

    private Set<PropertyDefinition> properties;

    private String sourceItemType;

    private String targetItemType;

    /**
     * The display name used to represent this relationship from the source vertex's perspective.
     * e.g., if the source type is Agency, the target is Person, and the relationship name is EMPLOYS,
     * the source label might be "employs".
     */
    private String sourceLabel;

    /**
     *  The display name used to represent this relationship from the target vertex's perspective.
     *  e.g., if the source type is Agency, the target is Person, and the relationship name is EMPLOYS,
     *  the target label might be "worksFor".
     */
    private String targetLabel;

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

    public String getSourceItemType() {
        return sourceItemType;
    }

    public void setSourceItemType(String sourceItemType) {
        this.sourceItemType = sourceItemType;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getTargetItemType() {
        return targetItemType;
    }

    public void setTargetItemType(String targetItemType) {
        this.targetItemType = targetItemType;
    }

    public String getTargetLabel() {
        return targetLabel;
    }

    public void setTargetLabel(String targetLabel) {
        this.targetLabel = targetLabel;
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
        if (!(o instanceof LinkDefinition that)) return false;
        if (!super.equals(o)) return false;
        return Objects.equals(propertyGroups, that.propertyGroups)
                && Objects.equals(properties, that.properties)
                && Objects.equals(sourceItemType, that.sourceItemType)
                && Objects.equals(targetItemType, that.targetItemType)
                && Objects.equals(targetCardinality, that.targetCardinality)
                && Objects.equals(sourceCardinality, that.sourceCardinality)
                && sourceVersionAction == that.sourceVersionAction
                && targetVersionAction == that.targetVersionAction
                && Objects.equals(instanceMaxCardinality, that.instanceMaxCardinality);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), propertyGroups, properties, sourceItemType, targetItemType, targetCardinality, sourceCardinality, sourceVersionAction, targetVersionAction, instanceMaxCardinality);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", LinkDefinition.class.getSimpleName() + "[", "]")
                .add("propertyGroups=" + propertyGroups)
                .add("properties=" + properties)
                .add("sourceItemType='" + sourceItemType + "'")
                .add("targetItemType='" + targetItemType + "'")
                .add("targetCardinality=" + targetCardinality)
                .add("sourceCardinality=" + sourceCardinality)
                .add("sourceFollowLatest=" + sourceVersionAction)
                .add("targetFollowLatest=" + targetVersionAction)
                .add("instanceCardinality=" + instanceMaxCardinality)
                .add("description='" + description + "'")
                .add("uid='" + uid + "'")
                .toString();
    }
}
