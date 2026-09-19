package org.ntrloc.graph.mediaprocessor;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.ntrloc.mediaprocessor.grpc.ImageProcessorGrpc;
import org.ntrloc.mediaprocessor.grpc.VideoProcessorGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Talking to a media processor is optional -- most ntrloc deployments have no need for one. The
// dependency (media-processor-api, and grpc-stub/grpc-protobuf/protobuf-java transitively through
// it) is always on this app's classpath, same as Hazelcast is always present whether or not
// clustering is enabled (see org.ntrloc.graph.cluster.config) -- only channel/client construction
// here is conditional on media-processor.url actually being set.
@Configuration
@ConditionalOnProperty("media-processor.url")
public class MediaProcessorClientConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MediaProcessorClientConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel mediaProcessorChannel(@Value("${media-processor.url}") String target) {
        LOG.info("Connecting to media processor at {}", target);
        return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }

    @Bean
    public ImageProcessorGrpc.ImageProcessorStub imageProcessorStub(ManagedChannel mediaProcessorChannel) {
        return ImageProcessorGrpc.newStub(mediaProcessorChannel);
    }

    @Bean
    public VideoProcessorGrpc.VideoProcessorStub videoProcessorStub(ManagedChannel mediaProcessorChannel) {
        return VideoProcessorGrpc.newStub(mediaProcessorChannel);
    }
}
