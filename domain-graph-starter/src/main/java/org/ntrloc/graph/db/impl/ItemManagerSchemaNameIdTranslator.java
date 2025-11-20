package org.ntrloc.graph.db.impl;

import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutationResponse;
import org.ntrloc.graph.db.language.mutation.ItemMutationWithItemType;
import org.ntrloc.graph.db.language.mutation.ItemMutationWithLinks;
import org.ntrloc.graph.db.language.mutation.LinkMutation;
import org.ntrloc.graph.db.language.mutation.LinkMutationResponse;
import org.ntrloc.graph.db.language.mutation.LinkMutationWithLinkType;
import org.ntrloc.graph.db.language.mutation.LinkMutationWithProperties;
import org.ntrloc.graph.db.language.mutation.MutationWithProperties;
import org.ntrloc.graph.db.language.projection.AllLinksProjectionSpec;
import org.ntrloc.graph.db.language.projection.IncomingLinkProjection;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.ItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.LinksProjectionSpec;
import org.ntrloc.graph.db.language.projection.OutgoingLinkProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.SpecificLinksProjectionSpec;
import org.ntrloc.graph.db.language.selectors.ItemSelector;
import org.ntrloc.graph.db.language.selectors.ItemTypeSelector;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Provides translations between public representations of schema definitions and their internal IDs.
 */
@Component
public class ItemManagerSchemaNameIdTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(ItemManagerSchemaNameIdTranslator.class);

    private SchemaManager schemaManager;
    private Map<String, ItemDefinitionPublicToPrivateMapping> itemDefinitionPublicToPrivateMapping;
    private Map<String, ItemDefinitionPrivateToPublicMapping> itemDefinitionPrivateToPublicMapping;

    public ItemManagerSchemaNameIdTranslator(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        schemaManager.addSchemaChangeReaction(this::buildCaches);
    }

    private void buildCaches() {
        var itemDefinitions = schemaManager.retrieveItemDefinitions();
        var linkDefinitions = schemaManager.retrieveLinkDefinitions();

        itemDefinitionPublicToPrivateMapping = itemDefinitions.stream().collect(Collectors.toMap(ItemDefinition::getName, def -> new ItemDefinitionPublicToPrivateMapping(def, linkDefinitions)));
        itemDefinitionPrivateToPublicMapping = itemDefinitions.stream().collect(Collectors.toMap(ItemDefinition::getUid, def -> new ItemDefinitionPrivateToPublicMapping(def, linkDefinitions)));
    }

    /** Converts the type identifiers and properties of an item mutation and its links to private type IDs. */
    public ItemMutation convertPublicIdentifiersIoPrivate(ItemMutation mutation) {
        if (mutation instanceof ItemMutationWithItemType mutationWithItemType) {
            ItemDefinitionPublicToPrivateMapping publicMapping = itemDefinitionPublicToPrivateMapping.get(mutationWithItemType.getItemType());
            if (publicMapping == null) {
                throw new IllegalArgumentException("Unknown item type " + mutationWithItemType.getItemType());
            } else {
                publicMapping.convertMutation(mutationWithItemType);
                return mutation;
            }
        }
        return mutation;
    }

    /** Converts the type private type IDs of a mutation response to public labels. */
    public ItemMutationResponse convertPrivateIdentifiersIoPublic(ItemMutationResponse response) {
        ItemDefinitionPrivateToPublicMapping mapping = itemDefinitionPrivateToPublicMapping.get(response.getItemType());
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown item type ID " + response.getItemType());
        } else {
            mapping.convertItemMutation(response);
            return response;
        }
    }

    public LinkMutationResponse convertPrivateIdentifiersIoPublic(String itemTypeId, LinkMutationResponse response) {
        ItemDefinitionPrivateToPublicMapping mapping = itemDefinitionPrivateToPublicMapping.get(itemTypeId);
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown item type ID " + itemTypeId);
        } else {
            mapping.convertLinkMutation(response);
            return response;
        }
    }

    /** Converts the type identifiers and properties of an item projection spec to private type IDs. */
    public SelectableItemProjectionSpec convertPublicIdentifiersIoPrivate(SelectableItemProjectionSpec itemProjectionSpec) {
        ItemSelector selector = itemProjectionSpec.getItemSelector();
        if (selector instanceof ItemTypeSelector itemTypeSelector) {
            String itemType = itemTypeSelector.getItemType();
            ItemDefinitionPublicToPrivateMapping mapping = itemDefinitionPublicToPrivateMapping.get(itemType);
            mapping.convertProjection(itemProjectionSpec, itemTypeName -> itemDefinitionPublicToPrivateMapping.get(itemTypeName));
        } else {
            if (itemProjectionSpec.getLinks() instanceof SpecificLinksProjectionSpec) {
                throw new IllegalArgumentException("Specific links cannot be retrieved without using a LabelSelector");
            }
            if (itemProjectionSpec.getProperties() != null && !itemProjectionSpec.getProperties().isEmpty()) {
                throw new IllegalArgumentException("Specific properties cannot be retrieved without using a LabelSelector");
            }
        }
        return itemProjectionSpec;

    }

    /** Converts the type private type IDs of an item projection to public labels.  */
    public ItemProjection convertPrivateIdentifiersIoPublic(ItemProjection projection) {
        String itemType = projection.getItemType();
        ItemDefinitionPrivateToPublicMapping mapping = itemDefinitionPrivateToPublicMapping.get(itemType);
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown item type ID " + itemType);
        } else {
            mapping.convertProjection(projection, lookupItemType -> itemDefinitionPrivateToPublicMapping.get(lookupItemType));
            return projection;
        }
    }

    private class ItemDefinitionPublicToPrivateMapping {

        private ItemDefinition itemDefinition;
        private Map<String, String> propertyNameToIdMapping;

        /* Maps link labels to link IDs */
        private Map<String, LinkDefinitionPublicToPrivateMapping> linkMapping = new HashMap<>();

        ItemDefinitionPublicToPrivateMapping(ItemDefinition itemDefinition, Set<LinkDefinition> linkDefinitions) {
            this.itemDefinition = itemDefinition;
            propertyNameToIdMapping = itemDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getName, PropertyDefinition::getUid));

            Set<LinkDefinition> outItemLinkDefinitions = linkDefinitions.stream()
                    .filter(linkDefinition -> linkDefinition.getSourceItemType().equals(itemDefinition.getName()))
                    .collect(Collectors.toSet());
            for (LinkDefinition linkDefinition : outItemLinkDefinitions) {
                linkMapping.put(linkDefinition.getSourceLabel(), new LinkDefinitionPublicToPrivateMapping(linkDefinition, linkDefinition.getTargetItemType()));
            }
            Set<LinkDefinition> inItemLinkDefinitions = linkDefinitions.stream()
                    .filter(linkDefinition -> linkDefinition.getTargetItemType().equals(itemDefinition.getName()))
                    .collect(Collectors.toSet());
            for (LinkDefinition linkDefinition : inItemLinkDefinitions) {
                linkMapping.put(linkDefinition.getTargetLabel(), new LinkDefinitionPublicToPrivateMapping(linkDefinition, linkDefinition.getSourceItemType()));
            }
        }

        String getId() {
            return itemDefinition.getUid();
        }

        String getPropertyId(String propertyName) {
            return propertyNameToIdMapping.get(propertyName);
        }

        String getLinkId(String linkLabel) {
            return linkMapping.get(linkLabel).getLinkId();
        }

        String getLinkPropertyId(String linkLabel, String propertyName) {
            return linkMapping.get(linkLabel).getPropertyId(propertyName);
        }

        void convertMutation(ItemMutationWithItemType itemMutation) {
            itemMutation.setItemType(itemDefinition.getUid());
            if (itemMutation instanceof MutationWithProperties mutationWithProperties && mutationWithProperties.getProperties() != null) {
                List<Property> translatedProperties = mutationWithProperties.getProperties().stream().map(prop -> {
                    String publicPropertyName = prop.getName();
                    String propertyId = propertyNameToIdMapping.get(publicPropertyName);
                    if (propertyId == null) {
                        throw new IllegalArgumentException("Unknown property name " + publicPropertyName + " for item type " + itemMutation.getItemType());
                    } else {
                        return prop.renamedTo(propertyId);
                    }
                }).toList();
                mutationWithProperties.setProperties(translatedProperties);
            }
            if (itemMutation instanceof ItemMutationWithLinks<?> mutationWithLinks) {
                List<? extends LinkMutation> linkMutations = mutationWithLinks.getLinks();
                for  (LinkMutation linkMutation : linkMutations) {
                    if (linkMutation instanceof LinkMutationWithLinkType linkWithType) {
                        String linkType = linkWithType.getLinkType();
                        String linkId = linkMapping.get(linkType).getLinkId();
                        if (linkId == null) {
                            throw new IllegalArgumentException("Unknown link type " + linkType);
                        } else {
                            linkWithType.setLinkType(linkId);
                        }

                        if (linkMutation instanceof LinkMutationWithProperties linkWithProperties) {
                            List<Property> properties = linkWithProperties.getProperties();
                            List<Property> translatedProperties = properties.stream().map(p -> {
                                String linkPropertyName = p.getName();
                                Map<String, String> linkPropertyMapping = linkMapping.get(linkType).getProperties();
                                String linkPropertyId = linkPropertyMapping.get(linkPropertyName);
                                return p.renamedTo(linkPropertyId);
                            }).toList();
                            linkWithProperties.setProperties(translatedProperties);
                        }
                    }
                }
            }
        }

        void convertProjection(SelectableItemProjectionSpec itemProjectionSpec, Function<String, ItemDefinitionPublicToPrivateMapping> mappingLookupFunction) {
            String itemType = itemDefinition.getName();
            ItemTypeSelector newItemTypeSelector = new ItemTypeSelector(itemDefinition.getUid());
            itemProjectionSpec.setItemSelector(newItemTypeSelector);

            convertItemProjection(itemProjectionSpec, itemType, mappingLookupFunction);
        }

        private void convertItemProjection(ItemProjectionSpec itemProjectionSpec, String itemType, Function<String, ItemDefinitionPublicToPrivateMapping> mappingLookupFunction) {
            ItemDefinitionPublicToPrivateMapping mapping = itemType.equals(itemDefinition.getName()) ? this : mappingLookupFunction.apply(itemType);
            if (itemProjectionSpec.getProperties() != null) {
                List<String> translatedProperties = new ArrayList<>();
                for (String property : itemProjectionSpec.getProperties()) {
                    String propertyId = mapping.getPropertyId(property);
                    translatedProperties.add(propertyId);
                }
                itemProjectionSpec.setProperties(translatedProperties);
            }
            convertLinkProjections(itemProjectionSpec.getLinks(), mappingLookupFunction);
        }

        private void convertLinkProjections(LinksProjectionSpec linksProjectionSpec, Function<String, ItemDefinitionPublicToPrivateMapping> mappingLookupFunction) {
            switch (linksProjectionSpec) {
                case null -> { }
                case SpecificLinksProjectionSpec specificLinks -> {
                    for (LinkProjectionSpec linkProjectionSpec : specificLinks.getLinks()) {
                        String linkLabel = linkProjectionSpec.getLinkLabel();
                        String linkId = getLinkId(linkLabel);
                        if (linkId == null) {
                            throw new IllegalArgumentException("Unknown link " + linkProjectionSpec.getLinkLabel() + " for item type ID " + itemDefinition.getUid());
                        } else {
                            linkProjectionSpec.setLinkLabel(linkId);
                        }

                        List<String> linkProperties = linkProjectionSpec.getProperties();
                        if (linkProperties != null) {
                            List<String> translatedProperties = new ArrayList<>();
                            for (String property : linkProperties) {
                                String propertyId = getLinkPropertyId(linkLabel, property);
                                translatedProperties.add(propertyId);
                            }
                            linkProjectionSpec.setProperties(translatedProperties);
                        }

                        LinkDefinitionPublicToPrivateMapping linkDefMapping = linkMapping.get(linkLabel);
                        convertItemProjection(linkProjectionSpec.getItemProjectionSpec(), linkDefMapping.getRelatedItemType(), mappingLookupFunction);
                    }
                }
                case AllLinksProjectionSpec allLinksSpec -> {
                    LOG.info("Using all-links projection spec");
                }
                default -> {
                    // don't retrieve links if none were requested
                }
            }
        }

        private class LinkDefinitionPublicToPrivateMapping {
            private LinkDefinition linkDefinition;
            private String linkId;
            private String relatedItemType;
            private Map<String, String> linkPropertyNameToIdMapping;

            LinkDefinitionPublicToPrivateMapping(LinkDefinition linkDefinition, String relatedItemType) {
                this.linkDefinition = linkDefinition;
                this.linkId = linkDefinition.getUid();
                this.relatedItemType = relatedItemType;
                linkPropertyNameToIdMapping = linkDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getName, PropertyDefinition::getUid));
            }

            String getLinkId() {
                return linkId;
            }

            Map<String, String> getProperties() {
                return linkPropertyNameToIdMapping;
            }

            String getPropertyId(String propertyName) {
                return linkPropertyNameToIdMapping.get(propertyName);
            }

            String getRelatedItemType() {
                return relatedItemType;
            }
        }
    }

    private class ItemDefinitionPrivateToPublicMapping {
        private String itemDefinitionName;
        private Map<String, PropertyDefinition> propertyIdToDefinitionMapping;
        /* Maps link ids to link labels */
        private Map<String, String> linkIdToLabelMapping = new HashMap<>();
        /* Maps link ids to a map of property ids to names */
        private Map<String, Map<String, PropertyDefinition>> linkPropertyIdToDefinitionMapping = new HashMap<>();

        ItemDefinitionPrivateToPublicMapping(ItemDefinition itemDefinition, Set<LinkDefinition> linkDefinitions) {
            itemDefinitionName = itemDefinition.getName();
            propertyIdToDefinitionMapping = itemDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getUid, def -> def));
            Set<LinkDefinition> outItemLinkDefinitions = linkDefinitions.stream()
                    .filter(linkDefinition -> linkDefinition.getSourceItemType().equals(itemDefinition.getName()))
                    .collect(Collectors.toSet());
            for (LinkDefinition linkDefinition : outItemLinkDefinitions) {
                linkIdToLabelMapping.put(linkDefinition.getUid(), linkDefinition.getSourceLabel());
                linkPropertyIdToDefinitionMapping.put(linkDefinition.getUid(), linkDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getUid, def -> def)));
            }
            Set<LinkDefinition> inItemLinkDefinitions = linkDefinitions.stream()
                    .filter(linkDefinition -> linkDefinition.getTargetItemType().equals(itemDefinition.getName()))
                    .collect(Collectors.toSet());
            for (LinkDefinition linkDefinition : inItemLinkDefinitions) {
                linkIdToLabelMapping.put(linkDefinition.getUid(), linkDefinition.getTargetLabel());
                linkPropertyIdToDefinitionMapping.put(linkDefinition.getUid(), linkDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getUid, def -> def)));
            }
        }

        String getName() {
            return itemDefinitionName;
        }

        String getPropertyName(String propertyId) {
            return propertyIdToDefinitionMapping.get(propertyId).getName();
        }

        String getLinkLabel(String linkId) {
            return linkIdToLabelMapping.get(linkId);
        }

        void convertItemMutation(ItemMutationResponse mutationResponse) {
            mutationResponse.setItemType(itemDefinitionName);
        }

        void convertLinkMutation(LinkMutationResponse linkMutationResponse) {
            String linkLabel = linkIdToLabelMapping.get(linkMutationResponse.getLinkType());
            if (linkLabel == null) {
                throw new IllegalArgumentException("Unknown link id " + linkMutationResponse.getLinkid());
            } else {
                linkMutationResponse.setLinkType(linkLabel);
            }
        }

        void convertProjection(ItemProjection projection, Function<String, ItemDefinitionPrivateToPublicMapping> mappingLookupFunction) {
            convertItemProjection(projection, mappingLookupFunction);
        }

        private void convertItemProjection(ItemProjection projection, Function<String, ItemDefinitionPrivateToPublicMapping> mappingLookupFunction) {
            projection.setItemType(itemDefinitionName);
            if (projection.getProperties() != null) {
                Map<String, Object> properties = projection.getProperties();
                Function<String, PropertyDefinition> lookup = (s) -> propertyIdToDefinitionMapping.get(s);
                projection.setProperties(transformProperties(properties, lookup));

            }
            if (projection.getLinks() != null) {
                Map<String, List<LinkProjection>> links = projection.getLinks();
                List<LinkProjection> linkProjections = links.values().stream().flatMap(List::stream).toList();
                linkProjections.forEach(linkProjection -> this.convertLinkProjection(linkProjection, mappingLookupFunction));
                Map<String, List<LinkProjection>> linksMap = linkProjections.stream().collect(Collectors.groupingBy(LinkProjection::getLinkType));
                projection.setLinks(linksMap);
            }
        }

        private void convertLinkProjection(LinkProjection projection, Function<String, ItemDefinitionPrivateToPublicMapping> mappingLookupFunction) {
            String linkType = projection.getLinkType();
            String linkLabel = linkIdToLabelMapping.get(linkType);
            projection.setLinkType(linkLabel);
            if (projection.getProperties() != null) {
                Map<String, Object> properties = projection.getProperties();
                Function<String, PropertyDefinition> lookup = (s) -> linkPropertyIdToDefinitionMapping.get(linkType).get(s);
                projection.setProperties(transformProperties(properties, lookup));
            }
            if (projection instanceof OutgoingLinkProjection outgoing) {
                ItemDefinitionPrivateToPublicMapping targetTypeMapping = mappingLookupFunction.apply(outgoing.getTarget().getItemType());
                targetTypeMapping.convertItemProjection(outgoing.getTarget(), mappingLookupFunction);
            } else if (projection instanceof IncomingLinkProjection incoming) {
                ItemDefinitionPrivateToPublicMapping sourceTypeMapping = mappingLookupFunction.apply(incoming.getSource().getItemType());
                sourceTypeMapping.convertItemProjection(incoming.getSource(), mappingLookupFunction);
            }
        }

        private Map<String, Object> transformProperties(Map<String, Object> properties, Function<String, PropertyDefinition> propertyLookupFunction) {
            Map<String, Object> transformedProperties = new HashMap<>();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {

                String externalPropertyName;
                PropertyType propertyType;

                PropertyDefinition propertyDefinition = propertyLookupFunction.apply(entry.getKey());
                Object value = entry.getValue();

                if (propertyDefinition == null) {
                    externalPropertyName = entry.getKey();
                    propertyType = PropertyConstants.IMPLICIT_PROPERTY_TYPES.get(entry.getKey());
                } else {
                    externalPropertyName = propertyDefinition.getName();
                    propertyType = propertyDefinition.getType();
                }
                Object transformedValue = switch (propertyType) {
                    case BOOLEAN, DATE, DATETIME, DOUBLE, INT, STRING -> ((List)value).get(0);
                    default -> value;
                };
                transformedProperties.put(externalPropertyName, transformedValue);
            }
            return transformedProperties;
        }

    }

}
