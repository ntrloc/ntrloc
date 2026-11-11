package org.ntrloc.graph.draft;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

// The only way draft code reaches the cluster (notes §13.1): every topic it gets is named under "draft.",
// so draft code can't listen to, or publish on, a live topic. ArchitectureRulesTest keeps the rest of the
// draft package off ClusterService and Hazelcast, and live code out of the "draft." names.
@Component
public class DraftTopics {

    public static final String PREFIX = "draft.";

    private final ClusterService clusterService;

    public DraftTopics(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    /** A draft topic; `fromOtherNodes` hears what other nodes publish on it, never this node's own messages. */
    public <T> DraftTopic<T> topic(String name, Consumer<T> fromOtherNodes) {
        ITopic<T> topic = clusterService.getTopic(PREFIX + name);
        topic.addMessageListener(message -> {
            if (!message.getPublishingMember().equals(clusterService.getLocalMember())) {
                fromOtherNodes.accept(message.getMessageObject());
            }
        });
        return new DraftTopic<>(topic);
    }

    public static final class DraftTopic<T> {

        private final ITopic<T> topic;

        private DraftTopic(ITopic<T> topic) {
            this.topic = topic;
        }

        public void publish(T message) {
            topic.publish(message);
        }
    }
}
