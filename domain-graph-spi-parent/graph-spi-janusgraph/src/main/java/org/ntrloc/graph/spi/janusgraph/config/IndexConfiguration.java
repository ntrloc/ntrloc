package org.ntrloc.graph.spi.janusgraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "graph.index")
public class IndexConfiguration {

    private LuceneIndexConfiguration lucene;

    public LuceneIndexConfiguration getLucene() {
        return lucene;
    }

    public void setLucene(LuceneIndexConfiguration lucene) {
        this.lucene = lucene;
    }
}
