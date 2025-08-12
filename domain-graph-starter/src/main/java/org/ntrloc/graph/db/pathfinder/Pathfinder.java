package org.ntrloc.graph.db.pathfinder;

import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Iterator;
import java.util.Map;

public class Pathfinder {

    private GraphTraversalSource source;
    private String startLabel;
    private String endLabel;

    public Pathfinder(GraphTraversalSource source, String startLabel, String endLabel) {
        this.source = source;
        this.startLabel = startLabel;
        this.endLabel = endLabel;
    }

    public Iterator<PathNode> find() {
        Iterator<PathNode> rawIterator = findRaw();
        return new TransformingIterator<>(rawIterator, (this::compressNodes));
    }

    public Iterator<PathNode> findRaw() {
        return source.V().hasLabel(startLabel).as("start")
                .repeat(
                        __.bothE().as("e")
                                .otherV().as("v")
                                .simplePath()
                )
                .until(__.and(__.hasLabel(endLabel), __.not(__.in().in().hasLabel(endLabel)))) // stop tracing the path when you arrive at an end label that doesn't have an incoming endlabel link
                .path()

                // assuming the start and end of the path is always a vertex
                .by(
                        __.project("label", "id", "props").by(__.label()).by(__.id()).by(__.valueMap())

                ) // vertices get the label, id, and properties
                .by(
                        __.project("label", "id", "inV", "outV", "props").by(__.label()).by(__.id()).by(__.outV().id()).by(__.inV().id()).by(__.valueMap())
                ) // edge get the label, in vertex id, out vertex id, and properties

                .map(pathTraverser -> {
                    Path path = pathTraverser.get();

                    return buildNode(path, 0);
                })
        ;
    }

    private PathNode buildNode(Path path, int nodeIndex) {
        Map<String, Object> nodeObject = (Map) path.get(nodeIndex);
        PathNode node = new PathNode(nodeObject.get("id"), nodeObject.get("label").toString());
        node.setProperties((Map<String, Object>) nodeObject.get("props"));

        if (nodeIndex < path.size() - 1) {
            node.setNext(buildLink(path, nodeIndex + 1));
        }

        return node;

    }

    private PathLink buildLink(Path path, int nodeIndex) {
        Map<String, Object> linkObject = (Map) path.get(nodeIndex);
        PathLink link = new PathLink(linkObject.get("id"), linkObject.get("label").toString());
        link.setProperties((Map<String, Object>) linkObject.get("props"));

        if (nodeIndex == path.size() - 1) {
            throw new IllegalArgumentException("Link cannot be the last element in a path");
        } else {
            Map<String, Object> previous = (Map) path.get(nodeIndex - 1);
            Object inId = linkObject.get("inV"); // inV is the node the link is pointing towards

            Object previousNodeId = previous.get("id");
            if (inId.equals(previousNodeId)) {
                link.setDirection(PathLink.Direction.OUT);
            } else {
                link.setDirection(PathLink.Direction.IN);
            }

            link.setNext(buildNode(path, nodeIndex + 1));
        }

        return link;
    }

    /**
     * Compress a PathNode sequence into a simpler sequence where the raw intermediate relationship nodes
     * are compressed out of the path.
     */
    private PathNode compressNodes(PathNode node) {
        // in a raw pathnode sequence, the pattern will be <node>-<raw link>-<relationship node>-<raw link>-<node>.
        // we'll compress this into the pattern <node>-<link>-<node> where the properties on the link come from the original relationship node

        if (node.next != null) {
            PathLink link = node.next; // the raw link
            PathNode relationshipNode = link.getNext(); // the relationship node
            PathLink link2 = relationshipNode.next; // thw other raw link to the relationship node
            PathNode otherNode = link2.getNext();

            PathLink newLink = new PathLink(relationshipNode.getId(), relationshipNode.getLabel());
            newLink.setProperties(relationshipNode.getProperties());
            newLink.setDirection(link.getDirection());
            node.setNext(newLink);

            newLink.setNext(compressNodes(otherNode));
        }

        return node;
    }

}
