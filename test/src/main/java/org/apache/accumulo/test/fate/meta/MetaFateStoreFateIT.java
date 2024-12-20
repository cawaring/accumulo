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
package org.apache.accumulo.test.fate.meta;

import static org.apache.accumulo.core.fate.AbstractFateStore.createDummyLockID;
import static org.apache.accumulo.harness.AccumuloITBase.ZOOKEEPER_TESTING_SERVER;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.core.fate.AbstractFateStore.FateIdGenerator;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.FateStore;
import org.apache.accumulo.core.fate.ReadOnlyFateStore.TStatus;
import org.apache.accumulo.core.fate.zookeeper.MetaFateStore;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.test.fate.FateStoreIT;
import org.apache.accumulo.test.zookeeper.ZooKeeperTestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

@Tag(ZOOKEEPER_TESTING_SERVER)
public class MetaFateStoreFateIT extends FateStoreIT {

  private static ZooKeeperTestingServer szk = null;
  private static ZooReaderWriter zk = null;
  private static final InstanceId IID = InstanceId.of(UUID.randomUUID());
  private static final String ZK_ROOT = ZooUtil.getRoot(IID);

  @TempDir
  private static File tempDir;

  @BeforeAll
  public static void setup() throws Exception {
    szk = new ZooKeeperTestingServer(tempDir);
    zk = szk.getZooReaderWriter();
    zk.mkdirs(ZK_ROOT + Constants.ZFATE);
    zk.mkdirs(ZK_ROOT + Constants.ZTABLE_LOCKS);
  }

  @AfterAll
  public static void teardown() throws Exception {
    szk.close();
  }

  @Override
  public void executeTest(FateTestExecutor<TestEnv> testMethod, int maxDeferred,
      FateIdGenerator fateIdGenerator) throws Exception {
    ServerContext sctx = createMock(ServerContext.class);
    expect(sctx.getZooKeeperRoot()).andReturn(ZK_ROOT).anyTimes();
    expect(sctx.getZooReaderWriter()).andReturn(zk).anyTimes();
    replay(sctx);
    MetaFateStore<TestEnv> store = new MetaFateStore<>(ZK_ROOT + Constants.ZFATE, zk,
        createDummyLockID(), null, maxDeferred, fateIdGenerator);

    // Check that the store has no transactions before and after each test
    assertEquals(0, store.list().count());
    testMethod.execute(store, sctx);
    assertEquals(0, store.list().count());
  }

  @Override
  protected void deleteKey(FateId fateId, ServerContext sctx) {
    try {
      // We have to use reflection since the NodeValue is internal to the store

      // Grab both the constructors that use the serialized bytes and status, reservation
      Class<?> nodeClass = Class.forName(MetaFateStore.class.getName() + "$NodeValue");
      Constructor<?> statusReservationCons =
          nodeClass.getDeclaredConstructor(TStatus.class, FateStore.FateReservation.class);
      Constructor<?> serializedCons = nodeClass.getDeclaredConstructor(byte[].class);
      statusReservationCons.setAccessible(true);
      serializedCons.setAccessible(true);

      // Get the status and reservation fields so they can be read and get the serialize method
      Field nodeStatus = nodeClass.getDeclaredField("status");
      Field nodeReservation = nodeClass.getDeclaredField("reservation");
      Method nodeSerialize = nodeClass.getDeclaredMethod("serialize");
      nodeStatus.setAccessible(true);
      nodeReservation.setAccessible(true);
      nodeSerialize.setAccessible(true);

      // Get the existing status and reservation for the node and build a new node with an empty key
      // but uses the existing tid
      String txPath = ZK_ROOT + Constants.ZFATE + "/tx_" + fateId.getTxUUIDStr();
      Object currentNode = serializedCons.newInstance(new Object[] {zk.getData(txPath)});
      TStatus currentStatus = (TStatus) nodeStatus.get(currentNode);
      Optional<FateStore.FateReservation> currentReservation =
          getCurrentReservation(nodeReservation, currentNode);
      // replace the node with no key and just a tid and existing status and reservation
      Object newNode =
          statusReservationCons.newInstance(currentStatus, currentReservation.orElse(null));

      // Replace the transaction with the same status and reservation but no key
      zk.putPersistentData(txPath, (byte[]) nodeSerialize.invoke(newNode),
          NodeExistsPolicy.OVERWRITE);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<FateStore.FateReservation> getCurrentReservation(Field nodeReservation,
      Object currentNode) throws Exception {
    Object currentResAsObject = nodeReservation.get(currentNode);
    Optional<FateStore.FateReservation> currentReservation = Optional.empty();
    if (currentResAsObject instanceof Optional) {
      Optional<?> currentResAsOptional = (Optional<?>) currentResAsObject;
      if (currentResAsOptional.isPresent()
          && currentResAsOptional.orElseThrow() instanceof FateStore.FateReservation) {
        currentReservation =
            Optional.of((FateStore.FateReservation) currentResAsOptional.orElseThrow());
      }
    }
    return currentReservation;
  }
}
