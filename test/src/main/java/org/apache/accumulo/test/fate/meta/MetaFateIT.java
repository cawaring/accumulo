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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.core.fate.AbstractFateStore.FateIdGenerator;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.ReadOnlyFateStore.TStatus;
import org.apache.accumulo.core.fate.zookeeper.MetaFateStore;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.test.fate.FateIT;
import org.apache.accumulo.test.zookeeper.ZooKeeperTestingServer;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.zookeeper.KeeperException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

@Tag(ZOOKEEPER_TESTING_SERVER)
public class MetaFateIT extends FateIT {

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

    testMethod.execute(new MetaFateStore<>(ZK_ROOT + Constants.ZFATE, zk, createDummyLockID(), null,
        maxDeferred, fateIdGenerator), sctx);
  }

  @Override
  protected TStatus getTxStatus(ServerContext sctx, FateId fateId) {
    try {
      return getTxStatus(sctx.getZooReaderWriter(), fateId);
    } catch (KeeperException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  /*
   * Get the status of the TX from ZK directly. Unable to call MetaFateStore.getStatus because this
   * test thread does not have the reservation (the FaTE thread does)
   */
  private static TStatus getTxStatus(ZooReaderWriter zrw, FateId fateId)
      throws KeeperException, InterruptedException {
    zrw.sync(ZK_ROOT);
    String txdir = String.format("%s%s/tx_%s", ZK_ROOT, Constants.ZFATE, fateId.getTxUUIDStr());

    try (DataInputBuffer buffer = new DataInputBuffer()) {
      var serialized = zrw.getData(txdir);
      buffer.reset(serialized, serialized.length);
      return TStatus.valueOf(buffer.readUTF());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (KeeperException.NoNodeException e) {
      return TStatus.UNKNOWN;
    }

  }

}
