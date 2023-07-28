/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.manager.state;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.manager.state.TabletManagement;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.Ample;
import org.apache.accumulo.core.metadata.schema.Ample.ConditionalResult.Status;
import org.apache.accumulo.core.metadata.schema.Ample.DataLevel;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;

class MetaDataStateStore extends AbstractTabletStateStore implements TabletStateStore {

  protected final ClientContext context;
  protected final CurrentState state;
  private final String targetTableName;
  private final Ample ample;
  private final DataLevel level;
  protected final ArrayBlockingQueue<TabletManagement> knownStateChanges =
      new ArrayBlockingQueue<>(1_000);

  protected MetaDataStateStore(DataLevel level, ClientContext context, CurrentState state,
      String targetTableName) {
    super(context);
    this.level = level;
    this.context = context;
    this.state = state;
    this.ample = context.getAmple();
    this.targetTableName = targetTableName;
  }

  MetaDataStateStore(DataLevel level, ClientContext context, CurrentState state) {
    this(level, context, state, MetadataTable.NAME);
  }

  @Override
  public DataLevel getLevel() {
    return level;
  }

  @Override
  public ClosableIterator<TabletManagement> iterator() {
    return new TabletManagementScanner(context, TabletsSection.getRange(), state, targetTableName,
        knownStateChanges);
  }

  @Override
  public boolean addTabletStateChange(TabletManagement tablet) {
    return this.knownStateChanges.offer(tablet);
  }

  @Override
  public void unsuspend(Collection<TabletMetadata> tablets) throws DistributedStoreException {
    try (var tabletsMutator = ample.conditionallyMutateTablets()) {
      for (TabletMetadata tm : tablets) {
        if (tm.getSuspend() != null) {
          continue;
        }

        // ELASTICITY_TODO pending #3314, add conditional mutation check that tls.suspend exists
        tabletsMutator.mutateTablet(tm.getExtent()).requireAbsentOperation()
            .requirePrevEndRow(tm.getExtent().prevEndRow()).deleteSuspension()
            .submit(tabletMetadata -> tabletMetadata.getSuspend() == null);
      }

      boolean unacceptedConditions = tabletsMutator.process().values().stream()
          .anyMatch(conditionalResult -> conditionalResult.getStatus() != Status.ACCEPTED);
      if (unacceptedConditions) {
        throw new DistributedStoreException("Some mutations failed to satisfy conditions");
      }
    } catch (RuntimeException ex) {
      throw new DistributedStoreException(ex);
    }
  }

  @Override
  public String name() {
    return "Normal Tablets";
  }

  @Override
  protected void processSuspension(Ample.ConditionalTabletMutator tabletMutator, TabletMetadata tm,
      long suspensionTimestamp) {
    if (tm.hasCurrent()) {
      if (suspensionTimestamp >= 0) {
        tabletMutator.putSuspension(tm.getLocation().getServerInstance(), suspensionTimestamp);
      }
    }

    if (tm.getSuspend() != null && suspensionTimestamp < 0) {
      tabletMutator.deleteSuspension();
    }
  }
}
