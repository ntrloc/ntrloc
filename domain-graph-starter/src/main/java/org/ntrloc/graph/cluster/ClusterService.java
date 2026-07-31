package org.ntrloc.graph.cluster;

import com.hazelcast.cluster.Member;
import com.hazelcast.map.IMap;
import com.hazelcast.topic.ITopic;

import java.util.Set;

public interface ClusterService {

    /**
     * Returns true if this node is the leader node in the cluster.
     */
    boolean isLeaderNode();

    boolean isJoined();

    Set<Member> getClusterMembers();

    Member getLocalMember();

    void addClusterJoinReaction(ClusterJoinReaction reaction);

    <K, V> IMap<K, V> getMap(String name);

    <T> ITopic<T> getTopic(String name);

}
