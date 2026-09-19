package org.ntrloc.graph.mediaprocessor;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.ntrloc.mediaprocessor.grpc.ImageProcessorGrpc;
import org.ntrloc.mediaprocessor.grpc.VideoProcessorGrpc;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MediaProcessorClientConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(MediaProcessorClientConfiguration.class);

    @Test
    void noMediaProcessorUrl_registersNoClientBeans() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ManagedChannel.class);
            assertThat(context).doesNotHaveBean(ImageProcessorGrpc.ImageProcessorStub.class);
            assertThat(context).doesNotHaveBean(VideoProcessorGrpc.VideoProcessorStub.class);
        });
    }

    @Test
    void mediaProcessorUrlConfigured_registersChannelAndBothStubs() {
        // forTarget/build never actually dials anything -- gRPC channels connect lazily on first
        // call -- so this is safe with no media processor listening at localhost:9095. The
        // channel's destroyMethod = "shutdown" (see MediaProcessorClientConfiguration) closes it
        // when the runner tears the context down at the end of this block.
        contextRunner.withPropertyValues("media-processor.url=localhost:9095").run(context -> {
            assertThat(context).hasSingleBean(ManagedChannel.class);
            assertThat(context).hasSingleBean(ImageProcessorGrpc.ImageProcessorStub.class);
            assertThat(context).hasSingleBean(VideoProcessorGrpc.VideoProcessorStub.class);
        });
    }
}
