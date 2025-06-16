package org.nterloc.graph.cluster.impl;

import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nterloc.graph.cluster.ClusterJoinReaction;
import org.nterloc.graph.cluster.ClusterService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ClusterServiceImpl implements ClusterService, MembershipListener {

    private static final Logger LOG = LogManager.getLogger(ClusterServiceImpl.class);

    private HazelcastInstance hazelcast;

    private boolean isJoined = false;

    private Optional<Boolean> isLeaderOpt = Optional.empty();

    private List<ClusterJoinReaction> joinReactions = new ArrayList<>();

    public ClusterServiceImpl(Config config) {
        LOG.info("Joining cluster");
        hazelcast = Hazelcast.getOrCreateHazelcastInstance(config);
        hazelcast.getCluster().addMembershipListener(this);
        isJoined = true;
        changeActiveStatusIfNecessary();
    }

    @PreDestroy
    private void shutdown() {
        LOG.warn("Shutting down cluster connection");
        hazelcast.shutdown();
    }

    @Override
    public boolean isLeaderNode() {
        return isLeaderOpt.isPresent() && isLeaderOpt.get();
    }

    @Override
    public boolean isJoined() {
        return isJoined;
    }

    @Override
    public Set<Member> getClusterMembers() {
        return hazelcast.getCluster().getMembers();
    }

    @Override
    public Member getLocalMember() {
        return hazelcast.getCluster().getLocalMember();
    }

    @Override
    public void addClusterJoinReaction(ClusterJoinReaction reaction) {
        joinReactions.add(reaction);
        if (isJoined) {
            joinReactions.forEach(ClusterJoinReaction::clusterJoined);
        }
    }

    @Override
    public <K, V> IMap<K, V> getMap(String name) {
        return hazelcast.getMap(name);
    }

    /*
     * Cluster membership methods
     */

    @Override
    public void memberAdded(MembershipEvent membershipEvent) {
        LOG.info("Got member added: {}", membershipEvent);
        changeActiveStatusIfNecessary();
    }

    @Override
    public void memberRemoved(MembershipEvent membershipEvent) {
        LOG.info("Got member removed: {}", membershipEvent);
        changeActiveStatusIfNecessary();
    }

    private void changeActiveStatusIfNecessary() {
        boolean nextLeaderStatus = shouldBeLeader();
        if (isLeaderOpt.isPresent()) {
            if (!isLeaderOpt.get().equals(nextLeaderStatus)) {
                LOG.debug("Leadership status changing from {} to {}", isLeaderOpt.get(), nextLeaderStatus);
                isLeaderOpt = Optional.of(nextLeaderStatus);
            } else {
                LOG.info("No leadership change on cluster membership change");
            }
        } else {
            LOG.info("Establishing initial leadership on cluster membership change");
            isLeaderOpt = Optional.of(nextLeaderStatus);
        }
    }

    private boolean shouldBeLeader() {
        Member oldestMember = hazelcast.getCluster().getMembers().iterator().next();
        Member me = hazelcast.getCluster().getLocalMember();
        return oldestMember.equals(me);
    }

}
