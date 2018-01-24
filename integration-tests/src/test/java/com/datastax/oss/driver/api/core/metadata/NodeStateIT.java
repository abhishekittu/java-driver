/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.api.core.metadata;

import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance;
import com.datastax.oss.driver.api.testinfra.cluster.SessionRule;
import com.datastax.oss.driver.api.testinfra.cluster.SessionUtils;
import com.datastax.oss.driver.api.testinfra.simulacron.SimulacronRule;
import com.datastax.oss.driver.api.testinfra.utils.ConditionChecker;
import com.datastax.oss.driver.categories.ParallelizableTests;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metadata.DefaultNode;
import com.datastax.oss.driver.internal.core.metadata.NodeStateEvent;
import com.datastax.oss.driver.internal.core.metadata.TopologyEvent;
import com.datastax.oss.driver.internal.testinfra.cluster.TestConfigLoader;
import com.datastax.oss.simulacron.common.cluster.ClusterSpec;
import com.datastax.oss.simulacron.common.cluster.NodeConnectionReport;
import com.datastax.oss.simulacron.common.stubbing.CloseType;
import com.datastax.oss.simulacron.server.BoundNode;
import com.datastax.oss.simulacron.server.RejectScope;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static com.datastax.oss.driver.assertions.Assertions.assertThat;
import static com.datastax.oss.driver.assertions.Assertions.fail;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

@Category(ParallelizableTests.class)
public class NodeStateIT {

  public @Rule SimulacronRule simulacron = new SimulacronRule(ClusterSpec.builder().withNodes(2));

  private NodeStateListener nodeStateListener = Mockito.mock(NodeStateListener.class);
  private InOrder inOrder;

  public @Rule SessionRule<CqlSession> sessionRule =
      SessionRule.builder(simulacron)
          .withOptions(
              "connection.pool.local.size = 2",
              "connection.reconnection-policy.max-delay = 1 second",
              String.format(
                  "load-balancing-policy.filter.class = \"%s$CustomNodeFilter\"",
                  NodeStateIT.class.getName()))
          .withNodeStateListeners(nodeStateListener)
          .build();

  private InternalDriverContext driverContext;
  private final BlockingQueue<NodeStateEvent> stateEvents = new LinkedBlockingDeque<>();

  private BoundNode simulacronControlNode;
  private BoundNode simulacronRegularNode;
  private DefaultNode metadataControlNode;
  private DefaultNode metadataRegularNode;

  @Before
  public void setup() {
    inOrder = Mockito.inOrder(nodeStateListener);

    driverContext = (InternalDriverContext) sessionRule.session().getContext();
    driverContext.eventBus().register(NodeStateEvent.class, stateEvents::add);

    // Sanity check: the driver should have connected to simulacron
    ConditionChecker.checkThat(
            () ->
                // 1 control connection + 2 pooled connections per node
                simulacron.cluster().getActiveConnections() == 5)
        .as("Connections established")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();

    // Find out which node is the control node, and identify the corresponding Simulacron and driver
    // metadata objects.
    simulacronControlNode = simulacronRegularNode = null;
    for (BoundNode boundNode : simulacron.cluster().getNodes()) {
      if (boundNode.getActiveConnections() == 3) {
        simulacronControlNode = boundNode;
      } else {
        simulacronRegularNode = boundNode;
      }
    }
    assertThat(simulacronControlNode).isNotNull();
    assertThat(simulacronRegularNode).isNotNull();

    Map<InetSocketAddress, Node> nodesMetadata = sessionRule.session().getMetadata().getNodes();
    metadataControlNode =
        (DefaultNode) nodesMetadata.get(simulacronControlNode.inetSocketAddress());
    metadataRegularNode =
        (DefaultNode) nodesMetadata.get(simulacronRegularNode.inetSocketAddress());

    // SessionRule uses all nodes as contact points, so we only get onUp notifications for them (no
    // onAdd)
    inOrder.verify(nodeStateListener, timeout(500)).onUp(metadataControlNode);
    inOrder.verify(nodeStateListener, timeout(500)).onUp(metadataRegularNode);
  }

  @After
  public void teardown() {
    Mockito.reset(nodeStateListener);
  }

  @Test
  public void should_report_connections_for_healthy_nodes() {
    ConditionChecker.checkThat(
            () -> {
              assertThat(metadataControlNode).isUp().hasOpenConnections(3).isNotReconnecting();
              assertThat(metadataRegularNode).isUp().hasOpenConnections(2).isNotReconnecting();
            })
        .as("Node metadata up-to-date")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
  }

  @Test
  public void should_keep_regular_node_up_when_still_one_connection() {
    simulacronRegularNode.rejectConnections(0, RejectScope.UNBIND);
    NodeConnectionReport report = simulacronRegularNode.getConnections();
    simulacron.cluster().closeConnection(report.getConnections().get(0), CloseType.DISCONNECT);

    ConditionChecker.checkThat(
            () -> assertThat(metadataRegularNode).isUp().hasOpenConnections(1).isReconnecting())
        .as("Reconnection started")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, never()).onDown(metadataRegularNode);
  }

  @Test
  public void should_mark_regular_node_down_when_no_more_connections() {
    simulacronRegularNode.stop();

    ConditionChecker.checkThat(
            () -> assertThat(metadataRegularNode).isDown().hasOpenConnections(0).isReconnecting())
        .as("Node going down")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();

    expect(NodeStateEvent.changed(NodeState.UP, NodeState.DOWN, metadataRegularNode));
    inOrder.verify(nodeStateListener, timeout(500)).onDown(metadataRegularNode);
  }

  @Test
  public void should_mark_control_node_down_when_control_connection_is_last_connection_and_dies() {
    simulacronControlNode.rejectConnections(0, RejectScope.UNBIND);

    // Identify the control connection and close the two other ones
    SocketAddress controlAddress = driverContext.controlConnection().channel().localAddress();
    NodeConnectionReport report = simulacronControlNode.getConnections();
    for (SocketAddress address : report.getConnections()) {
      if (!address.equals(controlAddress)) {
        simulacron.cluster().closeConnection(address, CloseType.DISCONNECT);
      }
    }
    ConditionChecker.checkThat(
            () -> assertThat(metadataControlNode).isUp().hasOpenConnections(1).isReconnecting())
        .as("Control node lost its non-control connections")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, never()).onDown(metadataRegularNode);

    simulacron.cluster().closeConnection(controlAddress, CloseType.DISCONNECT);
    ConditionChecker.checkThat(
            () -> assertThat(metadataControlNode).isDown().hasOpenConnections(0).isReconnecting())
        .as("Control node going down")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onDown(metadataControlNode);

    expect(NodeStateEvent.changed(NodeState.UP, NodeState.DOWN, metadataControlNode));
  }

  @Test
  public void should_bring_node_back_up_when_reconnection_succeeds() {
    simulacronRegularNode.stop();

    ConditionChecker.checkThat(
            () -> assertThat(metadataRegularNode).isDown().hasOpenConnections(0).isReconnecting())
        .as("Node going down")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onDown(metadataRegularNode);

    simulacronRegularNode.acceptConnections();

    ConditionChecker.checkThat(
            () -> assertThat(metadataRegularNode).isUp().hasOpenConnections(2).isNotReconnecting())
        .as("Connections re-established")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onUp(metadataRegularNode);

    expect(
        NodeStateEvent.changed(NodeState.UP, NodeState.DOWN, metadataRegularNode),
        NodeStateEvent.changed(NodeState.DOWN, NodeState.UP, metadataRegularNode));
  }

  @Test
  public void should_apply_up_and_down_topology_events_when_ignored() {
    CustomNodeFilter.IGNORED_NODES.add(metadataRegularNode);
    driverContext
        .loadBalancingPolicyWrapper()
        .setDistance(metadataRegularNode, NodeDistance.IGNORED);

    ConditionChecker.checkThat(
            () ->
                assertThat(metadataRegularNode)
                    .isUp()
                    .isIgnored()
                    .hasOpenConnections(0)
                    .isNotReconnecting())
        .as("Driver closed all connections to ignored node")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();

    driverContext
        .eventBus()
        .fire(TopologyEvent.suggestDown(metadataRegularNode.getConnectAddress()));
    ConditionChecker.checkThat(
            () ->
                assertThat(metadataRegularNode)
                    .isDown()
                    .isIgnored()
                    .hasOpenConnections(0)
                    .isNotReconnecting())
        .as("SUGGEST_DOWN event applied")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onDown(metadataRegularNode);

    driverContext.eventBus().fire(TopologyEvent.suggestUp(metadataRegularNode.getConnectAddress()));
    ConditionChecker.checkThat(
            () ->
                assertThat(metadataRegularNode)
                    .isUp()
                    .isIgnored()
                    .hasOpenConnections(0)
                    .isNotReconnecting())
        .as("SUGGEST_UP event applied")
        .before(10, TimeUnit.MINUTES)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onUp(metadataRegularNode);

    CustomNodeFilter.IGNORED_NODES.clear();
    driverContext.loadBalancingPolicyWrapper().setDistance(metadataRegularNode, NodeDistance.LOCAL);
  }

  @Test
  public void should_ignore_down_topology_event_when_still_connected() throws InterruptedException {
    driverContext
        .eventBus()
        .fire(TopologyEvent.suggestDown(metadataRegularNode.getConnectAddress()));
    TimeUnit.MILLISECONDS.sleep(500);
    assertThat(metadataRegularNode).isUp().hasOpenConnections(2).isNotReconnecting();
  }

  @Test
  public void should_force_immediate_reconnection_when_up_topology_event()
      throws InterruptedException {
    // This test requires a longer reconnection interval, so create a separate driver instance
    try (CqlSession session =
        SessionUtils.newSession(
            simulacron,
            "connection.reconnection-policy.base-delay = 1 hour",
            "connection.reconnection-policy.max-delay = 1 hour")) {
      NodeStateListener localNodeStateListener = Mockito.mock(NodeStateListener.class);
      session.register(localNodeStateListener);

      BoundNode localSimulacronNode = simulacron.cluster().getNodes().iterator().next();
      assertThat(localSimulacronNode).isNotNull();

      DefaultNode localMetadataNode =
          (DefaultNode)
              session.getMetadata().getNodes().get(localSimulacronNode.inetSocketAddress());

      localSimulacronNode.stop();

      ConditionChecker.checkThat(
              () -> assertThat(localMetadataNode).isDown().hasOpenConnections(0).isReconnecting())
          .as("Node going down")
          .before(10, TimeUnit.SECONDS)
          .becomesTrue();
      Mockito.verify(localNodeStateListener, timeout(500)).onDown(localMetadataNode);

      expect(NodeStateEvent.changed(NodeState.UP, NodeState.DOWN, localMetadataNode));

      localSimulacronNode.acceptConnections();
      ((InternalDriverContext) session.getContext())
          .eventBus()
          .fire(TopologyEvent.suggestUp(localMetadataNode.getConnectAddress()));

      ConditionChecker.checkThat(() -> assertThat(localMetadataNode).isUp().isNotReconnecting())
          .as("Node coming back up")
          .before(10, TimeUnit.SECONDS)
          .becomesTrue();
      Mockito.verify(localNodeStateListener, timeout(500)).onUp(localMetadataNode);

      expect(NodeStateEvent.changed(NodeState.DOWN, NodeState.UP, localMetadataNode));
    }
  }

  @Test
  public void should_force_down_when_not_ignored() throws InterruptedException {
    driverContext.eventBus().fire(TopologyEvent.forceDown(metadataRegularNode.getConnectAddress()));
    ConditionChecker.checkThat(
            () ->
                assertThat(metadataRegularNode)
                    .isForcedDown()
                    .hasOpenConnections(0)
                    .isNotReconnecting())
        .as("Node forced down")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onDown(metadataRegularNode);

    // Should ignore up/down topology events while forced down
    driverContext.eventBus().fire(TopologyEvent.suggestUp(metadataRegularNode.getConnectAddress()));
    TimeUnit.MILLISECONDS.sleep(500);
    assertThat(metadataRegularNode).isForcedDown();

    driverContext
        .eventBus()
        .fire(TopologyEvent.suggestDown(metadataRegularNode.getConnectAddress()));
    TimeUnit.MILLISECONDS.sleep(500);
    assertThat(metadataRegularNode).isForcedDown();

    // Should only come back up on a FORCE_UP event
    driverContext.eventBus().fire(TopologyEvent.forceUp(metadataRegularNode.getConnectAddress()));
    ConditionChecker.checkThat(
            () -> assertThat(metadataRegularNode).isUp().hasOpenConnections(2).isNotReconnecting())
        .as("Node forced back up")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onUp(metadataRegularNode);
  }

  @Test
  public void should_force_down_when_ignored() throws InterruptedException {
    CustomNodeFilter.IGNORED_NODES.add(metadataRegularNode);
    driverContext
        .loadBalancingPolicyWrapper()
        .setDistance(metadataRegularNode, NodeDistance.IGNORED);

    driverContext.eventBus().fire(TopologyEvent.forceDown(metadataRegularNode.getConnectAddress()));
    ConditionChecker.checkThat(
            () ->
                assertThat(metadataRegularNode)
                    .isForcedDown()
                    .hasOpenConnections(0)
                    .isNotReconnecting())
        .as("Node forced down")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onDown(metadataRegularNode);

    // Should ignore up/down topology events while forced down
    driverContext.eventBus().fire(TopologyEvent.suggestUp(metadataRegularNode.getConnectAddress()));
    TimeUnit.MILLISECONDS.sleep(500);
    assertThat(metadataRegularNode).isForcedDown();

    driverContext
        .eventBus()
        .fire(TopologyEvent.suggestDown(metadataRegularNode.getConnectAddress()));
    TimeUnit.MILLISECONDS.sleep(500);
    assertThat(metadataRegularNode).isForcedDown();

    // Should only come back up on a FORCE_UP event, will not reopen connections since it is still
    // ignored
    driverContext.eventBus().fire(TopologyEvent.forceUp(metadataRegularNode.getConnectAddress()));
    ConditionChecker.checkThat(
            () ->
                assertThat(metadataRegularNode)
                    .isUp()
                    .isIgnored()
                    .hasOpenConnections(0)
                    .isNotReconnecting())
        .as("Node forced back up")
        .before(10, TimeUnit.SECONDS)
        .becomesTrue();
    inOrder.verify(nodeStateListener, timeout(500)).onUp(metadataRegularNode);

    CustomNodeFilter.IGNORED_NODES.clear();
    driverContext.loadBalancingPolicyWrapper().setDistance(metadataRegularNode, NodeDistance.LOCAL);
  }

  @Test
  public void should_signal_non_contact_points_as_added() {
    // Since we need to observe the behavior of non-contact points, build a dedicated session with
    // just one contact point.
    Iterator<InetSocketAddress> contactPoints = simulacron.getContactPoints().iterator();
    InetSocketAddress address1 = contactPoints.next();
    InetSocketAddress address2 = contactPoints.next();
    NodeStateListener localNodeStateListener = Mockito.mock(NodeStateListener.class);
    try (CqlSession localSession =
        CqlSession.builder()
            .addContactPoint(address1)
            .addNodeStateListeners(localNodeStateListener)
            .withConfigLoader(
                new TestConfigLoader(
                    "connection.reconnection-policy.base-delay = 1 hour",
                    "connection.reconnection-policy.max-delay = 1 hour",
                    // Ensure we only have the control connection
                    "connection.pool.local.size = 0"))
            .build()) {

      Map<InetSocketAddress, Node> nodes = localSession.getMetadata().getNodes();
      Node localMetadataNode1 = nodes.get(address1);
      Node localMetadataNode2 = nodes.get(address2);

      // Successful contact point goes to up directly
      Mockito.verify(localNodeStateListener, timeout(500)).onUp(localMetadataNode1);
      // Non-contact point only added since we don't have a connection or events for it yet
      Mockito.verify(localNodeStateListener, timeout(500)).onAdd(localMetadataNode2);
    }
  }

  @Test
  public void should_remove_invalid_contact_point() {

    Iterator<InetSocketAddress> contactPoints = simulacron.getContactPoints().iterator();
    InetSocketAddress address1 = contactPoints.next();
    InetSocketAddress address2 = contactPoints.next();
    NodeStateListener localNodeStateListener = Mockito.mock(NodeStateListener.class);

    // Initialize the driver with 1 wrong address and 1 valid address
    InetSocketAddress wrongContactPoint = withUnusedPort(address1);
    try (CqlSession localSession =
        CqlSession.builder()
            .addContactPoint(address1)
            .addContactPoint(wrongContactPoint)
            .addNodeStateListeners(localNodeStateListener)
            .withConfigLoader(
                new TestConfigLoader(
                    "connection.reconnection-policy.base-delay = 1 hour",
                    "connection.reconnection-policy.max-delay = 1 hour"))
            .build()) {

      Map<InetSocketAddress, Node> nodes = localSession.getMetadata().getNodes();
      assertThat(nodes).doesNotContainKey(wrongContactPoint);
      Node localMetadataNode1 = nodes.get(address1);
      Node localMetadataNode2 = nodes.get(address2);

      // The order of the calls is not deterministic because contact points are shuffled, but it
      // does not matter here since Mockito.verify does not enforce order.
      Mockito.verify(localNodeStateListener, timeout(500))
          .onRemove(new DefaultNode(wrongContactPoint));
      Mockito.verify(localNodeStateListener, timeout(500)).onUp(localMetadataNode1);
      Mockito.verify(localNodeStateListener, timeout(500)).onAdd(localMetadataNode2);

      // Note: there might be an additional onDown for wrongContactPoint if it was hit first at
      // init. This is hard to test since the node was removed later, so we simply don't call
      // verifyNoMoreInteractions.
    }
  }

  @Test
  public void should_mark_unreachable_contact_point_down() {
    // This time we connect with two valid contact points, but is unresponsive, it should be marked
    // down
    Iterator<BoundNode> simulacronNodes = simulacron.cluster().getNodes().iterator();
    BoundNode localSimulacronNode1 = simulacronNodes.next();
    BoundNode localSimulacronNode2 = simulacronNodes.next();

    InetSocketAddress address1 = localSimulacronNode1.inetSocketAddress();
    InetSocketAddress address2 = localSimulacronNode2.inetSocketAddress();

    NodeStateListener localNodeStateListener = Mockito.mock(NodeStateListener.class);

    localSimulacronNode2.stop();
    try {
      // Since contact points are shuffled, we have a 50% chance that our bad contact point will be
      // hit first. So we retry the scenario a few times if needed.
      for (int i = 0; i < 10; i++) {
        try (CqlSession localSession =
            CqlSession.builder()
                .addContactPoint(address1)
                .addContactPoint(address2)
                .addNodeStateListeners(localNodeStateListener)
                .withConfigLoader(
                    new TestConfigLoader(
                        "connection.reconnection-policy.base-delay = 1 hour",
                        "connection.reconnection-policy.max-delay = 1 hour"))
                .build()) {

          Mockito.verify(localNodeStateListener).onRegister(localSession);

          Map<InetSocketAddress, Node> nodes = localSession.getMetadata().getNodes();
          Node localMetadataNode1 = nodes.get(address1);
          Node localMetadataNode2 = nodes.get(address2);
          if (localMetadataNode2.getState() == NodeState.DOWN) {
            // Stopped node was tried first and marked down, that's our target scenario
            Mockito.verify(localNodeStateListener, timeout(500)).onDown(localMetadataNode2);
            Mockito.verify(localNodeStateListener, timeout(500)).onUp(localMetadataNode1);
            Mockito.verifyNoMoreInteractions(localNodeStateListener);
            return;
          } else {
            // Stopped node was not tried
            assertThat(localMetadataNode2).isUnknown();
            Mockito.verify(localNodeStateListener, timeout(500)).onUp(localMetadataNode1);
            Mockito.verifyNoMoreInteractions(localNodeStateListener);
          }
        }
        Mockito.reset(localNodeStateListener);
      }
      fail("Couldn't get the driver to try stopped node first (tried 5 times)");
    } finally {
      localSimulacronNode2.acceptConnections();
    }
  }

  @Test
  public void should_call_onRegister_and_onUnregister_implicitly() {
    NodeStateListener localNodeStateListener = Mockito.mock(NodeStateListener.class);
    CqlSession localSessionRef;
    // onRegister should be called implicitly when added as a listener on builder.
    try (CqlSession localSession =
        CqlSession.builder()
            .addContactPoints(simulacron.getContactPoints())
            .addNodeStateListeners(localNodeStateListener)
            .build()) {
      Mockito.verify(localNodeStateListener).onRegister(localSession);
      localSessionRef = localSession;
    }
    // onUnregister should be called implicitly when cluster is closed.
    Mockito.verify(localNodeStateListener).onUnregister(localSessionRef);
  }

  @Test
  public void should_call_onRegister_and_onUnregister_when_used() {
    NodeStateListener localNodeStateListener = Mockito.mock(NodeStateListener.class);
    sessionRule.session().register(localNodeStateListener);
    Mockito.verify(localNodeStateListener, timeout(1000)).onRegister(sessionRule.session());
    sessionRule.session().unregister(localNodeStateListener);
    Mockito.verify(localNodeStateListener, timeout(1000)).onUnregister(sessionRule.session());
  }

  private void expect(NodeStateEvent... expectedEvents) {
    for (NodeStateEvent expected : expectedEvents) {
      try {
        NodeStateEvent actual = stateEvents.poll(10, TimeUnit.SECONDS);
        assertThat(actual).isEqualTo(expected);
      } catch (InterruptedException e) {
        fail("Interrupted while waiting for event");
      }
    }
  }

  // Generates a socket address that is not the connect address of one of the nodes in the cluster
  private InetSocketAddress withUnusedPort(InetSocketAddress address) {
    return new InetSocketAddress(address.getAddress(), findAvailablePort());
  }

  /**
   * Finds an available port in the ephemeral range. This is loosely inspired by Apache MINA's
   * AvailablePortFinder.
   */
  private static synchronized int findAvailablePort() throws RuntimeException {
    // let the system pick an ephemeral port
    try (ServerSocket ss = new ServerSocket(0)) {
      ss.setReuseAddress(true);
      return ss.getLocalPort();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  // Hack to allow tests to dynamically configure which nodes should be ignored
  public static class CustomNodeFilter implements Predicate<Node> {
    public static Set<Node> IGNORED_NODES = Sets.newConcurrentHashSet();

    public CustomNodeFilter(@SuppressWarnings("unused") DriverContext context) {}

    @Override
    public boolean test(Node node) {
      return !IGNORED_NODES.contains(node);
    }
  }
}