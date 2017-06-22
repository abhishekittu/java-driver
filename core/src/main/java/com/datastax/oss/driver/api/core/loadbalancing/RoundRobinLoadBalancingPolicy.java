/*
 * Copyright (C) 2017-2017 DataStax Inc.
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
package com.datastax.oss.driver.api.core.loadbalancing;

import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A round-robin load balancing policy.
 *
 * <p>It assigns distance {@link NodeDistance#LOCAL} to all up nodes. Each query plan returns all
 * the nodes, starting at an incrementing index and traversing the list in a circular fashion.
 */
public class RoundRobinLoadBalancingPolicy implements LoadBalancingPolicy {
  private static final Logger LOG = LoggerFactory.getLogger(RoundRobinLoadBalancingPolicy.class);

  private static final IntUnaryOperator INCREMENT = i -> (i == Integer.MAX_VALUE) ? 0 : i + 1;

  private final String logPrefix;
  private final AtomicInteger startIndex = new AtomicInteger();
  private final CopyOnWriteArraySet<Node> liveNodes = new CopyOnWriteArraySet<>();

  public RoundRobinLoadBalancingPolicy(@SuppressWarnings("unused") DriverContext context) {
    this.logPrefix = context.clusterName();
  }

  @Override
  public void init(Set<Node> nodes, DistanceReporter distanceReporter) {
    LOG.debug("[{}] Initializing with {}", logPrefix, nodes);
    for (Node node : nodes) {
      distanceReporter.setDistance(node, NodeDistance.LOCAL);
      if (node.getState() == NodeState.UNKNOWN || node.getState() == NodeState.UP) {
        this.liveNodes.add(node);
      }
    }
  }

  @Override
  public Queue<Node> newQueryPlan() {
    Object[] snapshot = liveNodes.toArray();
    int myStartIndex = startIndex.getAndUpdate(INCREMENT);
    ConcurrentLinkedQueue<Node> plan = new ConcurrentLinkedQueue<>();
    for (int i = 0; i < snapshot.length; i++) {
      Node node = (Node) snapshot[(myStartIndex + i) % liveNodes.size()];
      plan.offer(node);
    }
    return plan;
  }

  @Override
  public void onAdd(Node node) {
    onUp(node);
  }

  @Override
  public void onUp(Node node) {
    LOG.debug("[{}] Adding {}", logPrefix, node);
    liveNodes.add(node);
  }

  @Override
  public void onDown(Node node) {
    LOG.debug("[{}] Removing {}", logPrefix, node);
    liveNodes.remove(node);
  }

  @Override
  public void onRemove(Node node) {
    onDown(node);
  }

  @Override
  public void close() {
    // nothing to do
  }
}