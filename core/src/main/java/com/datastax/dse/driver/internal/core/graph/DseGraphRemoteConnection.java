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
package com.datastax.dse.driver.internal.core.graph;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.jcip.annotations.Immutable;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnectionException;
import org.apache.tinkerpop.gremlin.process.remote.traversal.RemoteTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;

@Immutable
public class DseGraphRemoteConnection implements RemoteConnection {

  private final CqlSession session;
  private final DriverExecutionProfile executionProfile;
  private final String executionProfileName;

  public DseGraphRemoteConnection(
      CqlSession session, DriverExecutionProfile executionProfile, String executionProfileName) {
    this.session = session;
    this.executionProfile = executionProfile;
    this.executionProfileName = executionProfileName;
  }

  @Override
  @SuppressWarnings("deprecation")
  public <E> Iterator<Traverser.Admin<E>> submit(Traversal<?, E> traversal)
      throws RemoteConnectionException {
    return submit(traversal.asAdmin().getBytecode());
  }

  @Override
  @SuppressWarnings({"deprecation", "unchecked"})
  public <E> RemoteTraversal<?, E> submit(Bytecode bytecode) throws RemoteConnectionException {
    try {
      return (RemoteTraversal<?, E>) submitAsync(bytecode).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RemoteConnectionException(e);
    }
  }

  @Override
  public <E> CompletableFuture<RemoteTraversal<?, E>> submitAsync(Bytecode bytecode)
      throws RemoteConnectionException {
    return session
        .executeAsync(new BytecodeGraphStatement(bytecode, executionProfile, executionProfileName))
        .toCompletableFuture()
        .thenApply(DseGraphTraversal::new);
  }

  @Override
  public void close() throws Exception {
    // do not close the DseSession here.
  }
}
