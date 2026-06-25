package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.definition.PropertyUsage;
import org.ntrloc.graph.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.schema.definition.view.admin.PropertyTypeView;
import org.ntrloc.graph.schema.definition.view.calculated.ItemDefinitionView;
import org.ntrloc.graph.schema.definition.view.calculated.ItemLinkPerspectiveView;
import org.ntrloc.graph.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.schema.repository.SchemaRepository;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@DependsOn("schemaInitializer")
public class SchemaManager {

    private final SchemaRepository repo;

    public SchemaManager(SchemaRepository repo) {
        this.repo = repo;
        init();
    }

    private void init() {
        var product     = repo.createItem("Product", "A product (book, DVD, etc.) sold by the company");
        var cover       = repo.createItem("Cover", "A cover for a product");
        var altCover    = repo.createItem("AlternateCover", "An alternate cover for a product");
        var contributor = repo.createItem("Contributor", "A person who contributed to a product in some way (author, illustrator, editor, etc.)");

        var isbn = repo.createProperty("ISBN 13", null, PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL);
        repo.associateItemProperty(product, isbn.id());

        var productCoverLink = repo.createLink();
        var createDate = repo.createProperty("createDate", null, PropertyType.DATE, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL);
        repo.associateLinkProperty(productCoverLink, createDate.id());
        repo.createPerspective(product, productCoverLink, "cover", null, 0, 1);
        repo.createPerspective(cover, productCoverLink, "product", null, 1, 1);

        var productAltCoverLink = repo.createLink();
        repo.createPerspective(product, productAltCoverLink, "cover", null, 0, 1);
        repo.createPerspective(altCover, productAltCoverLink, "product", null, 1, 1);

        var productContributorLink = repo.createLink();
        repo.createPerspective(product, productContributorLink, "contributors", null, 0, null);
        repo.createPerspective(contributor, productContributorLink, "product", null, 0, null);
    }

    public SchemaView getSchema() {
        var items = repo.getAllItems();
        var itemMap = items.stream().collect(Collectors.toMap(SchemaRepository.ItemRow::id, r -> r));
        var propertiesByItem = repo.getPropertiesByItem();
        var propertiesByLink = repo.getPropertiesByLink();
        var perspectivesByItem = repo.getPerspectivesByItem();

        var itemViews = items.stream().map(item -> {
            var props = propertiesByItem.get(item.id());
            var propViews = props == null ? null : props.stream()
                    .map(p -> new org.ntrloc.graph.schema.definition.view.calculated.PropertyDefinitionView(p.id(), p.name(), p.description(), p.type(), p.cardinality()))
                    .toList();

            var perspectives = perspectivesByItem.get(item.id());
            var links = perspectives == null ? null : perspectives.stream().map(p -> {
                var inverse = repo.findInversePerspective(p.linkId(), p.id());
                var inverseItem = itemMap.get(inverse.itemId());
                var linkProps = propertiesByLink.get(p.linkId());
                var linkPropViews = linkProps == null ? null : linkProps.stream()
                        .map(lp -> new org.ntrloc.graph.schema.definition.view.calculated.PropertyDefinitionView(lp.id(), lp.name(), lp.description(), lp.type(), lp.cardinality()))
                        .toList();
                return Map.entry(p.name(), new ItemLinkPerspectiveView(inverseItem.name(), p.description(), p.minCardinality(), p.maxCardinality(), linkPropViews));
            }).collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

            return new ItemDefinitionView(item.id(), item.name(), item.description(), propViews, links);
        }).toList();

        return new SchemaView(itemViews);
    }

    public AdminSchemaView getAdminSchema() {
        var items = repo.getAllItems();
        var itemMap = items.stream().collect(Collectors.toMap(SchemaRepository.ItemRow::id, r -> r));
        var propertiesByItem = repo.getPropertiesByItem();
        var propertiesByLink = repo.getPropertiesByLink();
        var perspectivesByItem = repo.getPerspectivesByItem();

        var itemViews = items.stream().map(item -> {
            var props = propertiesByItem.get(item.id());

            var perspectives = perspectivesByItem.get(item.id());
            var links = perspectives == null ? null : perspectives.stream().map(p -> {
                var inverse = repo.findInversePerspective(p.linkId(), p.id());
                var inverseItem = itemMap.get(inverse.itemId());
                var linkProps = propertiesByLink.get(p.linkId());
                return Map.entry(p.name(), new AdminItemLinkPerspectiveView(inverseItem.name(), p.description(), p.minCardinality(), p.maxCardinality(), linkProps));
            }).collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

            return new AdminItemDefinitionView(item.id(), item.name(), item.description(), props, links);
        }).toList();

        List<PropertyTypeView> propertyTypes = Arrays.stream(PropertyType.values())
                .map(type -> new PropertyTypeView(type, type.validCardinalities()))
                .toList();

        return new AdminSchemaView(itemViews, propertyTypes);
    }
}
