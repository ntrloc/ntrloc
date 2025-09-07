package org.ntrloc.graph.cluster.impl;

import com.hazelcast.cluster.MembershipEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.config.StandaloneClusterConfigurationFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("A cluster service")
class ClusterServiceTest {

    private ClusterServiceImpl service;

    @BeforeEach
    void init() {
        service = new ClusterServiceImpl(new StandaloneClusterConfigurationFactory("testCluster").getObject());
    }

    @Test
    @DisplayName("should be able to shut down")
    void testShutdown() {
        service.shutdown();
    }

    @Test
    @DisplayName("should report being the leader")
    void testIsLeader() {
       assertTrue(service.isLeaderNode());
    }

    @Test
    @DisplayName("should report being joined")
    void testJoined() {
        assertTrue(service.isJoined());
    }

    @Test
    @DisplayName("should report cluster members")
    void testReportClusterMembers() {
        assertFalse(service.getClusterMembers().isEmpty());
    }

    @Test
    @DisplayName("should get the local cluster member")
    void testGetLocalMember() {
        assertNotNull(service.getLocalMember());
    }

    @Test
    @DisplayName("should add a cluster join reaction")
    void testAddClusterJoinReaction() {
        AtomicBoolean reactionCalled = new AtomicBoolean(false);
        service.addClusterJoinReaction(() -> reactionCalled.set(true));
        await().atMost(5, TimeUnit.SECONDS).until(() -> reactionCalled.get());
    }

    @Test
    @DisplayName("should get a map")
    void testGetMap() {
        assertNotNull(service.getMap("testMap"));
    }

    @Test
    @DisplayName("should handle a member being added")
    void testMemberAdded() {
        service.memberAdded(mock(MembershipEvent.class));
    }

    @Test
    @DisplayName("should handle a member being removed")
    void testMemberRemoved() {
        service.memberRemoved(mock(MembershipEvent.class));
    }

}
