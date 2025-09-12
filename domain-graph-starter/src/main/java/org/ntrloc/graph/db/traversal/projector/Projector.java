package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.__;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.language.projection.IncomingLinkProjection;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.ItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.OutgoingLinkProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.selectors.HasPropertyValueSelector;
import org.ntrloc.graph.db.language.selectors.ItemSelector;
import org.ntrloc.graph.db.language.selectors.predicate.EqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.GreaterThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.LessThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.NotEqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.Predicate;
import org.ntrloc.graph.db.language.selectors.predicate.WithinPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.WithoutPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class Projector {

    private static final Logger LOG = LoggerFactory.getLogger(Projector.class);

    private GraphTraversalSource traversalSource;

    public Projector(GraphTraversalSource traversalSource) {
        this.traversalSource = traversalSource;
    }

    public List<ItemProjection> project(SelectableItemProjectionSpec spec) {
        GraphTraversal<?, Vertex> traversal = traversalSource.V();
        traversal = select(traversal, spec);
        var projectionTraversal = projectItems(traversal, spec, spec.getItemType());

        List<ItemProjection> itemProjections = new ArrayList<>();
        while (projectionTraversal.hasNext()) {
            var next =  projectionTraversal.next();
            itemProjections.add(next);
        }
        return itemProjections;
    }

    /** Returns a new traverser that adds a node selection to the given traversal. */
    private GraphTraversal<?, Vertex> select(GraphTraversal<?, Vertex> traversal, SelectableItemProjectionSpec spec) {
        var retTraversal =  traversal.has(PropertyConstants.ITEM_TYPE_PROPERTY, spec.getItemType());
        if (spec.getItemSelector() != null) {
            var selectTraversal = getItemSelectionTraversal(spec.getItemType(), spec.getItemSelector());
            retTraversal = retTraversal.and(selectTraversal);
        }
        return retTraversal;
    }

    /** Returns a traverser that adds a property projection to the given traverser. */
    private GraphTraversal<?, ItemProjection> projectItems(GraphTraversal<?, Vertex> traversal, ItemProjectionSpec spec, String itemType) {
        Map<String, GraphTraversal<?, ?>> projectionTraversals = new TreeMap<>();
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.VERSION_PROPERTY, __.values(PropertyConstants.VERSION_PROPERTY));
        projectionTraversals.put(PropertyConstants.IS_LATEST_VERSION_PROPERTY,  __.choose(
                __.inE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).limit(1),
                __.constant(false),
                __.constant(true)
        ));
        projectionTraversals.put(PropertyConstants.ITEM_TYPE_PROPERTY, __.values(PropertyConstants.ITEM_TYPE_PROPERTY));

        if (spec.getProperties() != null) {
            var internalPropertyNames = spec.getProperties().stream().map(p -> externalToInternalPropertyName(itemType, p)).toList();
            projectionTraversals.put("properties", __.valueMap(internalPropertyNames.toArray(new String[0])));
        }

        if (spec.getLinks() != null) {
            for (Map.Entry<String, LinkProjectionSpec> entry : spec.getLinks().entrySet()) {
                String linkAlias = entry.getKey();
                LinkProjectionSpec linkSpec = entry.getValue();
                String otherNodeName = linkSpec.getRelatedItemType();
                var linkTraversal = linkSpec.getDirection().equals(Direction.IN) ?
                        __.in(getLinkPropertyOutEdgeName(linkSpec.getLinkName()))
                                .where(__.in(getLinkPropertyInEdgeName(linkSpec.getLinkName())).has(PropertyConstants.ITEM_TYPE_PROPERTY, otherNodeName))
                        :
                        __.out(getLinkPropertyInEdgeName(linkSpec.getLinkName()))
                                .where(__.out(getLinkPropertyOutEdgeName(linkSpec.getLinkName())).has(PropertyConstants.ITEM_TYPE_PROPERTY, otherNodeName));
                projectionTraversals.put(linkAlias, projectLinks(linkTraversal, linkSpec));
            }
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }

        return projectionTraversal.map(input -> {
            Map<String, Object> value = input.get();
            String uid = (String) value.get(PropertyConstants.UNIQUE_ID_PROPERTY);
            int version = (int) value.get(PropertyConstants.VERSION_PROPERTY);
            boolean isLatestVersion = (boolean) value.get(PropertyConstants.IS_LATEST_VERSION_PROPERTY);
            String iType = (String) value.get(PropertyConstants.ITEM_TYPE_PROPERTY);
            Map<String, Object> nodeProps = (Map<String, Object>) value.get("properties");
            Map<String, Object> translatedProps = nodeProps == null ? null : nodeProps.entrySet().stream()
                    .collect(Collectors.toMap(entry -> internalToExternalPropertyName(iType, entry.getKey()), Map.Entry::getValue));

            ItemProjection projection = new ItemProjection();
            projection.setId(uid);
            projection.setVersion(version);
            projection.setLatestVersion(isLatestVersion);
            projection.setItemType(iType);
            projection.setProperties(translatedProps);

            if (spec.getLinks() != null) {
                Map<String, List<LinkProjection>> links = new HashMap<>();
                for (var entry: spec.getLinks().keySet()) {
                    links.put(entry, (List<LinkProjection>) value.get(entry));
                }
                projection.setLinks(links);
            }

            return projection;
        });
    }

    private GraphTraversal<?, List<LinkProjection>> projectLinks(GraphTraversal<?, Vertex> traversal, LinkProjectionSpec spec) {
        Map<String, GraphTraversal<?, ?>> projectionTraversals = new TreeMap<>();
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.ITEM_TYPE_PROPERTY, __.values(PropertyConstants.ITEM_TYPE_PROPERTY));
        if (spec.getProperties() != null) {
            projectionTraversals.put("properties", __.valueMap(spec.getProperties().toArray(new String[0])));
        }

        ItemProjectionSpec itemProjectionSpec = spec.getItemProjectionSpec();
        if (itemProjectionSpec == null) {
            itemProjectionSpec = new ItemProjectionSpec();
        }

        // add the node on the other side of the link to the projection
        if (spec.getDirection().equals(Direction.IN)) {
            var sourceTraversal = __.inE(getLinkPropertyInEdgeName(spec.getLinkName())).outV();
            var projectedNodes = projectItems(sourceTraversal, itemProjectionSpec, spec.getRelatedItemType());
            projectionTraversals.put("source", projectedNodes);
        } else {
            var targetTraversal = __.outE(getLinkPropertyOutEdgeName(spec.getLinkName())).inV();
            var projectedNodes = projectItems(targetTraversal, itemProjectionSpec, spec.getRelatedItemType());
            projectionTraversals.put("target", projectedNodes);
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }
        var foldTraversal = projectionTraversal.fold();
        return foldTraversal.map(inputs -> {
            List<Map<String, Object>> inputList = inputs.get();
            return inputList.stream().map(value -> {
                LinkProjection linkProjection = null;
                if (value.containsKey("source")) {
                    IncomingLinkProjection in = new IncomingLinkProjection();
                    in.setSource((ItemProjection) value.get("source"));
                    linkProjection = in;
                } else if (value.containsKey("target")) {
                    OutgoingLinkProjection out = new OutgoingLinkProjection();
                    out.setTarget((ItemProjection) value.get("target"));
                    linkProjection = out;
                } else {
                    LOG.warn("mapped link contains no source or target node");
                }
                linkProjection.setProperties((Map<String, Object>) value.get("properties"));

                return linkProjection;
            }).toList();
        });
    }

    private String externalToInternalPropertyName(String itemType, String propertyName) {
        return "%s_%s".formatted(itemType, propertyName);
    }

    private String internalToExternalPropertyName(String itemType, String propertyName) {
        return propertyName.replaceFirst("%s_%s".formatted(itemType, ""), "");
    }

    private String getLinkPropertyInEdgeName(String linkName) {
        return linkName + "-in";
    }

    private String getLinkPropertyOutEdgeName(String linkName) {
        return linkName + "-out";
    }

    private GraphTraversal<?, ?> getItemSelectionTraversal(String itemtYpe, ItemSelector selector) {
        return switch (selector) {
            case HasPropertyValueSelector valueSelector -> __.start().has(externalToInternalPropertyName(itemtYpe, valueSelector.getName()), getPredicate(valueSelector.getPredicate()));
            default -> throw new RuntimeException("Not implemented yet");
        };
    }

    private P<?> getPredicate(Predicate predicate) {
        return switch (predicate) {
            case EqualsPredicate eq -> P.eq(eq.getValue());
            case NotEqualsPredicate neq -> P.neq(neq.getValue());
            case LessThanPredicate lt -> P.lt(lt.getValue());
            case GreaterThanPredicate gt -> P.gt(gt.getValue());
            case WithinPredicate within -> P.within(within.getValue());
            case WithoutPredicate without -> P.without(without.getValue());
            default -> throw new RuntimeException("Not implemented yet");
        };
    }

}
