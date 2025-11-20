package org.ntrloc.graph.db.impl;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.MutationException;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.ItemDeleteMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutationResponse;
import org.ntrloc.graph.db.language.mutation.ItemUpdateMutation;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.mutation.LinkMutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationType;
import org.ntrloc.graph.db.language.mutation.ReferenceableItemMutation;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.selectors.IdSelector;
import org.ntrloc.graph.db.storage.BinaryContentInfo;
import org.ntrloc.graph.db.storage.BinaryContentInfoWithStream;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.ntrloc.graph.db.LabelConstants.DATA_LABEL;
import static org.ntrloc.graph.db.PropertyConstants.ITEM_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;

@Service
public class ItemManagerImpl implements ItemManager {

    private static final Logger LOG = LoggerFactory.getLogger(ItemManagerImpl.class);

    private GraphTraversalSource traversalSource;
    private BinaryStorageAdapter binaryStorageAdapter;
    private ItemManagerSchemaNameIdTranslator schemaNameIdTranslator;

    private Mutator mutator;
    private Projector projector;

    public ItemManagerImpl(GraphTraversalSource traversalSource, BinaryStorageAdapter binaryStorageAdapter, ItemManagerSchemaNameIdTranslator translator, Mutator mutator, Projector projector) {
        this.traversalSource = traversalSource;
        this.binaryStorageAdapter = binaryStorageAdapter;
        this.mutator = mutator;
        this.projector = projector;
        this.schemaNameIdTranslator = translator;
    }

    @Override
    public void resetGraph() {
        LOG.warn("Resetting graph. Dropping all vertices and edges.");
        var transaction = traversalSource.tx();
        transaction.begin();
        traversalSource.V().drop();
        traversalSource.E().drop();
        transaction.commit();
        transaction.close();

        // TODO: resetting the graph should also drop the property keys and indexes
    }

    /* -------------------------------- Binary methods -------------------------------- */

    @Override
    public HashingBinaryDataWriter openWriter() throws IOException {
        return binaryStorageAdapter.openWriter();
    }

    @Override
    public String commitBinary(HashingBinaryDataWriter writer) throws IOException {
        BinaryContentInfo hash = binaryStorageAdapter.close(writer);
        var iterator = traversalSource.V().hasLabel(DATA_LABEL)
                .has("sha256", hash.getSha256Hash())
                .has("md5", hash.getMd5Hash())
                .elementMap();
        if (iterator.hasNext()) {
            return (String) iterator.next().get(UNIQUE_ID_PROPERTY);
        } else {
            var transaction = traversalSource.tx();
            String uid;
            GraphTraversal<Vertex, Vertex> traversal;
            do {
                uid = UUID.randomUUID().toString();
                traversal = traversalSource.V().hasLabel(DATA_LABEL).has(UNIQUE_ID_PROPERTY, uid);
            } while (traversal.hasNext());

            Map<Object, Object> dataProps = Map.of(
                    UNIQUE_ID_PROPERTY, uid,
                    ITEM_TYPE_PROPERTY, DATA_LABEL,
                    "sha256", hash.getSha256Hash(),
                    "md5", hash.getMd5Hash(),
                    "mimeType", hash.getMimeType(),
                    "length", hash.getLength()

            );
            traversalSource.addV(DATA_LABEL).property(dataProps).next();
            LOG.info("Wrote binary with UID {}, SHA-256 {}, MD5 {}, mimeType {}", uid, hash.getSha256Hash(), hash.getMd5Hash(), hash.getMimeType());

            LOG.info("Committing transaction {}", transaction);
            transaction.commit();
            transaction.close();

            var confirmIter = traversalSource.V().hasLabel(DATA_LABEL).has(UNIQUE_ID_PROPERTY, uid).valueMap();
            assert(confirmIter.hasNext()) : "Data node with UID " + uid + " not found after commit";
            LOG.info("Confirmed binary node with values {}", confirmIter.next());

            return uid;
        }
    }

    @Override
    public Optional<BinaryContentInfoWithStream> getBinaryStream(String uuid) throws IOException {
        var iterator = traversalSource.V().hasLabel(DATA_LABEL).has(UNIQUE_ID_PROPERTY, uuid).elementMap();
        if (iterator.hasNext()) {
            Map<Object, Object> elements = iterator.next();
            String sha256 = (String) elements.get("sha256");
            String md5Hash = (String) elements.get("md5");
            String mimeType = (String) elements.get("mimeType");
            Long length = (Long) elements.get("length");
            InputStream stream = binaryStorageAdapter.openReader(sha256, md5Hash);
            BinaryContentInfoWithStream contentInfoWithStream = new BinaryContentInfoWithStream(sha256, md5Hash);
            contentInfoWithStream.setLength(length);
            contentInfoWithStream.setMimeType(mimeType);
            contentInfoWithStream.setBinaryStream(stream);
            return Optional.of(contentInfoWithStream);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public void abandonBinary(HashingBinaryDataWriter writer) {
        binaryStorageAdapter.abandon(writer);
    }

    /* -------------------------------- Mutation methods -------------------------------- */

    @Override
    public MutationResponse executeMutation(MutationRequest mutationRequest) {
        LOG.info("Executing mutation {}", mutationRequest);

        var response = new MutationResponse();
        mutator.begin();

        List<ItemMutation> itemMutations = mutationRequest.getItemMutations().stream().map(schemaNameIdTranslator::convertPublicIdentifiersIoPrivate).toList();

        Set<ItemCreateMutation> createMutations = itemMutations.stream().filter(ItemCreateMutation.class::isInstance)
                .map(ItemCreateMutation.class::cast).collect(Collectors.toSet());
        Set<ItemUpdateMutation> updateMutations = itemMutations.stream().filter(ItemUpdateMutation.class::isInstance)
                .map(ItemUpdateMutation.class::cast).collect(Collectors.toSet());
        Set<ItemDeleteMutation> deleteMutations = itemMutations.stream().filter(ItemDeleteMutation.class::isInstance)
                .map(ItemDeleteMutation.class::cast).collect(Collectors.toSet());

        Map<String, ReferenceableItemMutation> mutationIdMap = new HashMap<>();

        for (ItemCreateMutation createMutation: createMutations) {
            String createdId = mutator.createNode(createMutation.getItemType(), createMutation.getProperties());
            mutationIdMap.put(createdId, createMutation);
            ItemMutationResponse item = new ItemMutationResponse(MutationType.CREATE, createMutation.getItemType(), createdId);
            response.addItemMutationResponse(item);
        }

        for (ItemUpdateMutation updateMutation: updateMutations) {
            String itemType = mutator.updateNode(updateMutation.getId(), updateMutation.getProperties());
            ItemMutationResponse item = new ItemMutationResponse(MutationType.UPDATE, itemType, updateMutation.getId());
            response.addItemMutationResponse(item);
        }

        for (ItemDeleteMutation deleteMutation: deleteMutations) {
            String deleteId = deleteMutation.getId();
            String itemType = mutator.deleteNode(deleteId);
            ItemMutationResponse item = new ItemMutationResponse(MutationType.DELETE, itemType, deleteId);
            response.addItemMutationResponse(item);
        }

        // -------------------------- start link section

        for (ItemCreateMutation createMutation: createMutations) {
            String fromId = null;
            Optional<Map.Entry<String, ReferenceableItemMutation>> createdEntryOpt = mutationIdMap.entrySet().stream().filter(entry -> entry.getValue() == createMutation).findAny();
            if (createdEntryOpt.isPresent()) {
                fromId = createdEntryOpt.get().getKey();
            } else {
                throw new MutationException("Item mutation " + createMutation + " not found");
            }

            for (LinkCreateMutation linkCreateMutation: createMutation.getLinks()) {
                var selector = linkCreateMutation.getSelector();
                if (!(selector instanceof IdSelector)) {
                    throw new MutationException("Selector must be an ID selector");
                }
                var idSelector = (IdSelector)selector;
                String toId = switch(idSelector.getType()) {
                    case IdSelector.Type.LOCAL -> {
                        // find the ID of the item whose reference matches this reference ID
                        Optional<Map.Entry<String, ReferenceableItemMutation>> mutationEntryOpt = mutationIdMap.entrySet()
                                .stream()
                                .filter(entry -> entry.getValue().getRefId() != null && entry.getValue().getRefId().equals(idSelector.getId()))
                                .findAny();
                        if (mutationEntryOpt.isPresent()) {
                            yield mutationEntryOpt.get().getKey();
                        } else {
                            throw new MutationException("Linked item " + idSelector.getId() + " not found");
                        }
                    }
                    case IdSelector.Type.GLOBAL -> idSelector.getId();
                };
                if (toId == null) {
                    throw new MutationException("Linked item " + idSelector.getId() + " not found");
                } else {
                    LOG.info("Linking item to item {}", toId);
                    String linkId = mutator.createLink(fromId, toId, linkCreateMutation.getLinkType(), linkCreateMutation.getProperties());

                    LinkMutationResponse link = new LinkMutationResponse(MutationType.CREATE, linkCreateMutation.getLinkType(), linkId, fromId, toId);
                    link = schemaNameIdTranslator.convertPrivateIdentifiersIoPublic(createMutation.getItemType(), link);
                    response.addLinkMutationResponse(link);
                }
            }
        }

        // -------------------------- end link section

        mutator.prepare();
        mutator.commit();

        response.getItemMutationResponses().forEach(schemaNameIdTranslator::convertPrivateIdentifiersIoPublic);

        return response;
    }

    /* -------------------------------- Projection methods -------------------------------- */

    @Override
    public List<ItemProjection> executeProjection(SelectableItemProjectionSpec spec) {
        return executeProjection(spec, null);
    }

    @Override
    public List<ItemProjection> executeProjection(SelectableItemProjectionSpec spec, URI binaryDownloadUri) {
        SelectableItemProjectionSpec transformedSpec = schemaNameIdTranslator.convertPublicIdentifiersIoPrivate(spec);
        List<ItemProjection> projections = projector.project(transformedSpec, binaryDownloadUri);
        return projections.stream().map(schemaNameIdTranslator::convertPrivateIdentifiersIoPublic).toList();
    }

}
