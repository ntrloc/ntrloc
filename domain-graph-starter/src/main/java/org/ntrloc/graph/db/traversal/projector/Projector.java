package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.__;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.LabelConstants;
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
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.ntrloc.graph.db.LabelConstants.NODE_PROPERTY_EDGE_LABEL;
import static org.ntrloc.graph.db.PropertyConstants.ITEM_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.LINK_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.NODE_PROPERTY_NAME_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.STATUS_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.TRANSACTION_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.VERSION_PROPERTY;

@Component
public class Projector {

    private static final Logger LOG = LoggerFactory.getLogger(Projector.class);

    private static final List<String> SUPPRESSED_PROPERTIES = List.of(VERSION_PROPERTY, UNIQUE_ID_PROPERTY, STATUS_PROPERTY, TRANSACTION_ID_PROPERTY, ITEM_TYPE_PROPERTY, LINK_TYPE_PROPERTY);

    private GraphTraversalSource traversalSource;

    public Projector(GraphTraversalSource traversalSource) {
        this.traversalSource = traversalSource;
    }

    public List<ItemProjection> project(SelectableItemProjectionSpec spec) {
        traversalSource.tx().close();
        traversalSource.tx().begin();
        return project(spec, null);
    }

    public List<ItemProjection> project(SelectableItemProjectionSpec spec, URI binaryDownloadUri) {
        GraphTraversal<?, Vertex> traversal = traversalSource.V();
        traversal = select(traversal, spec);

        var projectionTraversal = projectItems(traversal, spec, binaryDownloadUri);

        List<ItemProjection> itemProjections = new ArrayList<>();
        while (projectionTraversal.hasNext()) {
            var next =  projectionTraversal.next();
            itemProjections.add(next);
        }
        return itemProjections;
    }

    /** Returns a new traverser that adds a node selection to the given traversal. */
    private GraphTraversal<?, Vertex> select(GraphTraversal<?, Vertex> traversal, SelectableItemProjectionSpec spec) {
        var retTraversal = switch (spec.getItemSelector()) {
            case ItemTypeSelector labelSelector -> traversal.has(PropertyConstants.ITEM_TYPE_PROPERTY, labelSelector.getItemType());
            case IdSelector idSelector -> traversal.has(PropertyConstants.UNIQUE_ID_PROPERTY, idSelector.getId());
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
    private GraphTraversal<?, ItemProjection> projectItems(GraphTraversal<?, Vertex> traversal, ItemProjectionSpec spec, URI binaryDownloadUri) {

        Map<String, GraphTraversal<?, ?>> projectionTraversals = new LinkedHashMap<>();
        projectionTraversals.put("id", __.id());
        projectionTraversals.put("label", __.label());
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.VERSION_PROPERTY, __.values(PropertyConstants.VERSION_PROPERTY));
        projectionTraversals.put(PropertyConstants.IS_LATEST_VERSION_PROPERTY,  __.choose(
                __.inE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).limit(1),
                __.constant(false),
                __.constant(true)
        ));
        projectionTraversals.put(PropertyConstants.ITEM_TYPE_PROPERTY, __.values(PropertyConstants.ITEM_TYPE_PROPERTY));

        if (spec.getProperties() == null) {
            // this will map any "actual" properties to the "properties" projection field
            projectionTraversals.put("properties", __.valueMap());
            projectionTraversals.put("nodeProperties", __.outE(NODE_PROPERTY_EDGE_LABEL).group().by(__.values(NODE_PROPERTY_NAME_PROPERTY)).by(__.inV().elementMap()));
        } else {
            String[] props = spec.getProperties().toArray(new String[0]);
            // this will map any "actual" properties to the "properties" projection field
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

        return projectionTraversal.map(input -> {
            Map<String, Object> value = input.get();
            String uid = (String) value.get(PropertyConstants.UNIQUE_ID_PROPERTY);
            int version = (int) value.get(PropertyConstants.VERSION_PROPERTY);
            boolean isLatestVersion = (boolean) value.get(PropertyConstants.IS_LATEST_VERSION_PROPERTY);
            String iType = (String) value.get(PropertyConstants.ITEM_TYPE_PROPERTY);

            Map<String, Object> properties = (Map<String, Object>) value.get("properties");
            Map<String, Map<String, Object>> nodePropsMap = (Map) value.get("nodeProperties");
            Map<String, BinaryProjection> binaryPropsMap = nodePropsMap.entrySet().stream().collect(Collectors.toMap(entry -> entry.getKey(), entry -> {
                        Map<String, Object> props = entry.getValue();
                        BinaryProjection binaryProp = new BinaryProjection();
                        binaryProp.setMd5((String) props.get("md5"));
                        binaryProp.setSha256((String) props.get("sha256"));
                        binaryProp.setMimeType((String) props.get("mimeType"));
                        binaryProp.setLength((Long) props.get("length"));
                        binaryProp.setId((String) props.get(UNIQUE_ID_PROPERTY));
                        if (binaryDownloadUri != null) {
                            try {
                                var downloadUri = binaryDownloadUri.resolve(new URI(binaryProp.getId())).toString();
                                binaryProp.setUrl(downloadUri);
                            } catch (URISyntaxException mue) {
                                LOG.error("Error setting binary download URI", mue);
                            }
                        }
                        return binaryProp;
            }));
            properties.putAll(binaryPropsMap);

            ItemProjection projection = new ItemProjection();
            projection.setId(uid);
            projection.setVersion(version);
            projection.setLatestVersion(isLatestVersion);
            projection.setItemType(iType);
            properties.keySet().removeAll(SUPPRESSED_PROPERTIES);
            projection.setProperties(properties);

            if (value.get("links") != null) {
                List<LinkProjection> links = (List<LinkProjection>) value.get("links");
                Map<String, List<LinkProjection>> linksMap = links.stream().collect(Collectors.groupingBy(LinkProjection::getLinkType));
                projection.setLinks(linksMap);
            }

            return projection;
        });
    }

    /** Creates a traversal that will use a links projection spec to produce a */
    private GraphTraversal<?, ArrayList<LinkProjection>> projectLinks(LinksProjectionSpec spec, URI binaryDownloadUri) {
        // here we want to create a projection either on all links, with a default node projection,
        // or specific links with a given node projection.

        List<GraphTraversal<Vertex, Map<String, Object>>> linkTraversals = new ArrayList<>();

        if (spec instanceof AllLinksProjectionSpec) {
            var genericOutboundTraversal = __.out().has(LINK_TYPE_PROPERTY)
                    .project(PropertyConstants.UNIQUE_ID_PROPERTY, "direction", "properties", "target", "linkType")
                    .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                    .by(__.constant(Direction.OUT))
                    .by(__.valueMap())
                    .by(projectItems(__.out(), new ItemProjectionSpec(), binaryDownloadUri))
                    .by(__.values(PropertyConstants.LINK_TYPE_PROPERTY));
            var genericInboundTraversal = __.in().has(LINK_TYPE_PROPERTY)
                    .project(PropertyConstants.UNIQUE_ID_PROPERTY, "direction", "properties", "source", "linkType")
                    .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                    .by(__.constant(Direction.IN))
                    .by(__.valueMap())
                    .by(projectItems(__.in(), new ItemProjectionSpec(), binaryDownloadUri))
                    .by(__.values(PropertyConstants.LINK_TYPE_PROPERTY));
            linkTraversals.add(genericOutboundTraversal);
            linkTraversals.add(genericInboundTraversal);
        } else if (spec instanceof SpecificLinksProjectionSpec specificLinksSpec) {
            Set<LinkProjectionSpec> linkProjectionSpecs = specificLinksSpec.getLinks();
            List<GraphTraversal<Vertex, Map<String, Object>>> travs = linkProjectionSpecs.stream().map(linkSpec -> {
                String linkType = linkSpec.getLinkLabel();
                Direction direction = linkSpec.getDirection();
                String otherNodeLabel = direction.equals(Direction.IN) ? "source" : "target";

                GraphTraversal<Vertex, Vertex> baseTraversal = direction.equals(Direction.IN) ? __.in() : __.out();
                baseTraversal = baseTraversal.has(LINK_TYPE_PROPERTY, linkType);
                return baseTraversal.project(PropertyConstants.UNIQUE_ID_PROPERTY, "direction", "properties", otherNodeLabel, "linkType")
                        .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                        .by(__.constant(linkSpec.getDirection()))
                        .by(__.valueMap())
                        .by(projectItems(direction.equals(Direction.IN) ? __.in() : __.out(), linkSpec.getItemProjectionSpec(), binaryDownloadUri))
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
                .by(__.fold())
                .map(linkMapTraversal -> {
                    Map<Object, Object> props = linkMapTraversal.get();
                    var retList = new ArrayList<LinkProjection>();
                    for (var entry : props.entrySet()) {
                        String linkType = (String) entry.getKey();
                        List<Map<String, Object>> values = (List<Map<String, Object>>) entry.getValue();

                        for (Map<String, Object> valueMap : values) {
                            Direction direction = (Direction) valueMap.get("direction");
                            if (direction == Direction.OUT) {
                                OutgoingLinkProjection linkProjection = new OutgoingLinkProjection();
                                linkProjection.setId((String) valueMap.get(UNIQUE_ID_PROPERTY));
                                linkProjection.setLinkType(linkType);
                                Map<String, Object> linkProperties = (Map<String, Object>) valueMap.get("properties");
                                linkProperties.keySet().removeAll(SUPPRESSED_PROPERTIES);
                                linkProjection.setProperties(linkProperties);
                                linkProjection.setTarget((ItemProjection)  valueMap.get("target"));
                                retList.add(linkProjection);
                            } else {
                                IncomingLinkProjection linkProjection = new IncomingLinkProjection();
                                linkProjection.setId((String) valueMap.get(UNIQUE_ID_PROPERTY));
                                linkProjection.setLinkType(linkType);
                                Map<String, Object> linkProperties = (Map<String, Object>) valueMap.get("properties");
                                linkProperties.keySet().removeAll(SUPPRESSED_PROPERTIES);
                                linkProjection.setProperties(linkProperties);
                                linkProjection.setSource((ItemProjection)  valueMap.get("source"));
                                retList.add(linkProjection);
                            }

                        }
                    }
                    return retList;
                });
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
