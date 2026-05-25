package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.__;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.PropertyConstants;
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
import org.ntrloc.graph.db.language.selectors.HasPropertyValueSelector;
import org.ntrloc.graph.db.language.selectors.IdSelector;
import org.ntrloc.graph.db.language.selectors.ItemTypeSelector;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ntrloc.graph.db.LabelConstants.NODE_PROPERTY_EDGE_LABEL;
import static org.ntrloc.graph.db.PropertyConstants.COMMIT_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.IS_LATEST_VERSION_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.ITEM_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.LINK_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.NODE_PROPERTY_NAME_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.VERSION_PROPERTY;

@Component
public class Projector {

    private static final Logger LOG = LoggerFactory.getLogger(Projector.class);

    // TODO shouldn't this derive from PropertyConstant implicit properties?
    private static final List<String> SUPPRESSED_PROPERTIES = List.of(VERSION_PROPERTY, COMMIT_ID_PROPERTY, UNIQUE_ID_PROPERTY, PropertyConstants.STATUS_PROPERTY, PropertyConstants.TRANSACTION_ID_PROPERTY, ITEM_TYPE_PROPERTY, LINK_TYPE_PROPERTY, IS_LATEST_VERSION_PROPERTY);

    private GraphTraversalSource traversalSource;

    public Projector(GraphTraversalSource traversalSource) {
        this.traversalSource = traversalSource;
    }

    public List<ItemProjection> project(SelectableItemProjectionSpec spec) {
        return project(spec, null);
    }

    public List<ItemProjection> project(SelectableItemProjectionSpec spec, URI binaryDownloadUri) {
        GraphTraversal<?, Vertex> traversal = select(traversalSource.V(), spec);
        List<Map<String, Object>> rawItems = projectItems(traversal, spec, binaryDownloadUri).toList();
        return rawItems.stream()
                .map(raw -> convertToItemProjection(raw, binaryDownloadUri))
                .toList();
    }

    /** Returns a new traverser that adds a node selection to the given traversal. */
    private GraphTraversal<?, Vertex> select(GraphTraversal<?, Vertex> traversal, SelectableItemProjectionSpec spec) {
        // ITEM_TYPE_PROPERTY is indexed and more selective than the isLatestVersion bool; apply it first.
        // For IdSelector, unique ID is maximally selective so it goes first.
        var retTraversal = switch (spec.getItemSelector()) {
            case ItemTypeSelector labelSelector -> traversal.has(ITEM_TYPE_PROPERTY, labelSelector.getItemType()).has(IS_LATEST_VERSION_PROPERTY, true);
            case IdSelector idSelector -> traversal.has(PropertyConstants.UNIQUE_ID_PROPERTY, idSelector.getId()).has(IS_LATEST_VERSION_PROPERTY, true);
            default -> throw new IllegalArgumentException("Invalid item selector " + spec.getItemSelector());
        };
        if (spec.getFilter() != null) {
            retTraversal = switch (spec.getFilter()) {
                case HasPropertyValueSelector valueSelector -> retTraversal.has(valueSelector.getName(), getPredicate(valueSelector.getPredicate()));
                default -> throw new RuntimeException("Not implemented yet");
            };
        }
        return retTraversal;
    }

    /** Returns a traverser that adds a property projection to the given traverser. */
    private GraphTraversal<?, Map<String, Object>> projectItems(GraphTraversal<?, Vertex> traversal, ItemProjectionSpec spec, URI binaryDownloadUri) {

        Map<String, GraphTraversal<?, ?>> projectionTraversals = new LinkedHashMap<>();
        projectionTraversals.put("id", __.id());
        projectionTraversals.put("label", __.label());
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.VERSION_PROPERTY, __.values(PropertyConstants.VERSION_PROPERTY));
        projectionTraversals.put(PropertyConstants.COMMIT_ID_PROPERTY, __.values(PropertyConstants.COMMIT_ID_PROPERTY));
        projectionTraversals.put(IS_LATEST_VERSION_PROPERTY, __.values(IS_LATEST_VERSION_PROPERTY));
        projectionTraversals.put(PropertyConstants.ITEM_TYPE_PROPERTY, __.values(PropertyConstants.ITEM_TYPE_PROPERTY));

        if (spec.getProperties() == null) {
            projectionTraversals.put("properties", __.valueMap());
            projectionTraversals.put("nodeProperties", __.outE(NODE_PROPERTY_EDGE_LABEL).group().by(__.values(NODE_PROPERTY_NAME_PROPERTY)).by(__.inV().elementMap()));
        } else if (!spec.getProperties().isEmpty()) {
            String[] props = spec.getProperties().toArray(new String[0]);
            projectionTraversals.put("properties", __.valueMap(props));
            projectionTraversals.put("nodeProperties", __.outE(NODE_PROPERTY_EDGE_LABEL).has(NODE_PROPERTY_NAME_PROPERTY, P.within(props)).group().by(__.values(NODE_PROPERTY_NAME_PROPERTY)).by(__.inV().elementMap()));
        }

        if (spec.getLinks() != null) {
            projectionTraversals.put("links", projectLinks(spec.getLinks(), binaryDownloadUri));
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }

        return projectionTraversal;
    }

    /** Creates a traversal that will use a links projection spec to produce a grouped raw link map. */
    private GraphTraversal<?, Map<Object, Object>> projectLinks(LinksProjectionSpec spec, URI binaryDownloadUri) {
        // here we want to create a projection either on all links, with a default node projection,
        // or specific links with a given node projection.

        List<GraphTraversal<Vertex, Map<String, Object>>> linkTraversals = new ArrayList<>();

        if (spec instanceof AllLinksProjectionSpec) {
            var genericOutboundTraversal = __.out()
                    .where(__.and(
                            __.has(LINK_TYPE_PROPERTY),
                            __.has(IS_LATEST_VERSION_PROPERTY, true),
                            __.filter(__.out().has(IS_LATEST_VERSION_PROPERTY, true))
                    ))
                    .project(PropertyConstants.UNIQUE_ID_PROPERTY, COMMIT_ID_PROPERTY, "direction", "properties", "target", "linkType")
                    .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                    .by(__.values(COMMIT_ID_PROPERTY))
                    .by(__.constant(Direction.OUT))
                    .by(__.valueMap())
                    .by(projectItems(__.out().has(IS_LATEST_VERSION_PROPERTY, true), new ItemProjectionSpec(), binaryDownloadUri))
                    .by(__.values(PropertyConstants.LINK_TYPE_PROPERTY));
            var genericInboundTraversal = __.in()
                    .where(__.and(
                            __.has(LINK_TYPE_PROPERTY),
                            __.has(IS_LATEST_VERSION_PROPERTY, true),
                            __.filter(__.in().has(IS_LATEST_VERSION_PROPERTY, true))
                    ))
                    .project(PropertyConstants.UNIQUE_ID_PROPERTY, COMMIT_ID_PROPERTY, "direction", "properties", "source", "linkType")
                    .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                    .by(__.values(COMMIT_ID_PROPERTY))
                    .by(__.constant(Direction.IN))
                    .by(__.valueMap())
                    .by(projectItems(__.in().has(IS_LATEST_VERSION_PROPERTY, true), new ItemProjectionSpec(), binaryDownloadUri))
                    .by(__.values(PropertyConstants.LINK_TYPE_PROPERTY));
            linkTraversals.add(genericOutboundTraversal);
            linkTraversals.add(genericInboundTraversal);
        } else if (spec instanceof SpecificLinksProjectionSpec specificLinksSpec) {
            Set<LinkProjectionSpec> linkProjectionSpecs = specificLinksSpec.getLinks();
            List<GraphTraversal<Vertex, Map<String, Object>>> travs = linkProjectionSpecs.stream().map(linkSpec -> {
                Direction direction = linkSpec.getDirection();
                String otherNodeLabel = direction.equals(Direction.IN) ? "source" : "target";

                GraphTraversal<Vertex, Vertex> baseTraversal = switch (direction) {
                    case OUT -> __.out()
                            .where(__.and(
                                    __.has(LINK_TYPE_PROPERTY),
                                    __.has(IS_LATEST_VERSION_PROPERTY, true),
                                    __.filter(__.out().has(IS_LATEST_VERSION_PROPERTY, true))
                            ));
                    case IN -> __.in()
                            .where(__.and(
                                    __.has(LINK_TYPE_PROPERTY),
                                    __.has(IS_LATEST_VERSION_PROPERTY, true),
                                    __.filter(__.in().has(IS_LATEST_VERSION_PROPERTY, true))
                            ));
                    case null, default -> throw new IllegalArgumentException("Direction must be specified for link projection spec");
                };

                var otherNodeTraversal = direction.equals(Direction.IN)
                        ? __.in().has(IS_LATEST_VERSION_PROPERTY, true)
                        : __.out().has(IS_LATEST_VERSION_PROPERTY, true);
                return baseTraversal.project(PropertyConstants.UNIQUE_ID_PROPERTY, COMMIT_ID_PROPERTY, "direction", "properties", otherNodeLabel, "linkType")
                        .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                        .by(__.values(COMMIT_ID_PROPERTY))
                        .by(__.constant(linkSpec.getDirection()))
                        .by(__.valueMap())
                        .by(projectItems(otherNodeTraversal, linkSpec.getItemProjectionSpec(), binaryDownloadUri))
                        .by(__.values(PropertyConstants.LINK_TYPE_PROPERTY));
            }).toList();
            linkTraversals.addAll(travs);
        } else {
            throw new IllegalArgumentException("Unknown links spec type: " + spec);
        }

        GraphTraversal<Vertex, Map<String, Object>>[] travArray = linkTraversals.toArray(GraphTraversal[]::new);
        return __.union(travArray)
                .group()
                .by(__.select("linkType"))
                .by(__.fold());
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<LinkProjection>> convertToLinkProjections(Map<?, ?> rawLinksMap, URI binaryDownloadUri) {
        Map<String, List<LinkProjection>> result = new LinkedHashMap<>();
        for (var entry : rawLinksMap.entrySet()) {
            String linkType = (String) entry.getKey();
            List<Map<String, Object>> values = (List<Map<String, Object>>) entry.getValue();
            List<LinkProjection> linkList = new ArrayList<>();
            for (Map<String, Object> valueMap : values) {
                Direction direction = (Direction) valueMap.get("direction");
                Map<String, Object> linkProperties = new LinkedHashMap<>((Map<String, Object>) valueMap.get("properties"));
                linkProperties.keySet().removeAll(SUPPRESSED_PROPERTIES);
                if (direction == Direction.OUT) {
                    OutgoingLinkProjection linkProjection = new OutgoingLinkProjection();
                    linkProjection.setId((String) valueMap.get(UNIQUE_ID_PROPERTY));
                    linkProjection.setCommitId((String) valueMap.get(COMMIT_ID_PROPERTY));
                    linkProjection.setLinkType(linkType);
                    linkProjection.setProperties(linkProperties);
                    linkProjection.setTarget(convertToItemProjection((Map<String, Object>) valueMap.get("target"), binaryDownloadUri));
                    linkList.add(linkProjection);
                } else {
                    IncomingLinkProjection linkProjection = new IncomingLinkProjection();
                    linkProjection.setId((String) valueMap.get(UNIQUE_ID_PROPERTY));
                    linkProjection.setCommitId((String) valueMap.get(COMMIT_ID_PROPERTY));
                    linkProjection.setLinkType(linkType);
                    linkProjection.setProperties(linkProperties);
                    linkProjection.setSource(convertToItemProjection((Map<String, Object>) valueMap.get("source"), binaryDownloadUri));
                    linkList.add(linkProjection);
                }
            }
            result.put(linkType, linkList);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ItemProjection convertToItemProjection(Map<String, Object> raw, URI binaryDownloadUri) {
        ItemProjection projection = new ItemProjection();
        projection.setId((String) raw.get(PropertyConstants.UNIQUE_ID_PROPERTY));
        projection.setVersion((Integer) raw.get(PropertyConstants.VERSION_PROPERTY));
        projection.setCommitId((String) raw.get(PropertyConstants.COMMIT_ID_PROPERTY));
        projection.setLatestVersion((Boolean) raw.get(PropertyConstants.IS_LATEST_VERSION_PROPERTY));
        projection.setItemType((String) raw.get(PropertyConstants.ITEM_TYPE_PROPERTY));

        if (raw.containsKey("properties")) {
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) raw.get("properties"));
            properties.keySet().removeAll(SUPPRESSED_PROPERTIES);
            projection.setProperties(properties);
        }
        if (raw.containsKey("links")) {
            projection.setLinks(convertToLinkProjections((Map<?, ?>) raw.get("links"), binaryDownloadUri));
        }
        return projection;
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
