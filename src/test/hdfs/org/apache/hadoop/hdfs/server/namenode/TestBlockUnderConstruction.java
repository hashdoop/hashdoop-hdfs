/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.TestFileCreation;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.BlockUCState;

import junit.framework.TestCase;

public class TestBlockUnderConstruction extends TestCase {
  static final String BASE_DIR = "/test/TestBlockUnderConstruction";
  static final int BLOCK_SIZE = 8192; // same as TestFileCreation.blocksize
  static final int NUM_BLOCKS = 5;  // number of blocks to write

  private MiniDFSCluster cluster;
  private DistributedFileSystem hdfs;

  protected void setUp() throws Exception {
    super.setUp();
    Configuration conf = new Configuration();
    cluster = new MiniDFSCluster(conf, 3, true, null);
    cluster.waitActive();
    hdfs = (DistributedFileSystem)cluster.getFileSystem();
  }

  protected void tearDown() throws Exception {
    if(hdfs != null) hdfs.close();
    if(cluster != null) cluster.shutdown();
    super.tearDown();
  }

  void writeFile(Path file, FSDataOutputStream stm, int size)
  throws IOException {
    long blocksBefore = stm.getPos() / BLOCK_SIZE;
    
    TestFileCreation.writeFile(stm, BLOCK_SIZE);
    int blocksAfter = 0;
    // wait until the block is allocated by DataStreamer
    BlockLocation[] locatedBlocks;
    while(blocksAfter <= blocksBefore) {
      locatedBlocks = hdfs.getClient().getBlockLocations(
          file.toString(), 0L, BLOCK_SIZE*NUM_BLOCKS);
      blocksAfter = locatedBlocks == null ? 0 : locatedBlocks.length;
    }
  }

  private void verifyFileBlocks(String file,
                                boolean isFileOpen) throws IOException {
    FSNamesystem ns = cluster.getNamesystem();
    INodeFile inode = ns.dir.getFileINode(file);
    assertTrue("File does not exist: " + inode.toString(), inode != null);
    assertTrue("File " + inode.toString() +
        " isUnderConstruction = " + inode.isUnderConstruction() +
        " expected to be " + isFileOpen,
        inode.isUnderConstruction() == isFileOpen);
    BlockInfo[] blocks = inode.getBlocks();
    assertTrue("File does not have blocks: " + inode.toString(),
        blocks != null && blocks.length > 0);
    
    int idx = 0;
    BlockInfo curBlock;
    // all blocks but the last two should be regular blocks
    for(; idx < blocks.length - 2; idx++) {
      curBlock = blocks[idx];
      assertFalse("Block is not under construction: " + curBlock,
          curBlock.isUnderConstruction());
      assertTrue("Block is not in BlocksMap: " + curBlock,
          ns.blockManager.getStoredBlock(curBlock) == curBlock);
    }

    // the penultimate block is either complete or
    // committed if the file is not closed
    if(idx > 0) {
      curBlock = blocks[idx-1]; // penultimate block
      assertTrue("Block " + curBlock +
          " isUnderConstruction = " + inode.isUnderConstruction() +
          " expected to be " + isFileOpen,
          (isFileOpen && !curBlock.isUnderConstruction()) ||
          (!isFileOpen && curBlock.isUnderConstruction() == 
            (curBlock.getBlockUCState() ==
              BlockUCState.COMMITTED)));
      assertTrue("Block is not in BlocksMap: " + curBlock,
          ns.blockManager.getStoredBlock(curBlock) == curBlock);
    }

    // the last block is under construction if the file is not closed
    curBlock = blocks[idx]; // last block
    assertTrue("Block " + curBlock +
        " isUnderConstruction = " + inode.isUnderConstruction() +
        " expected to be " + isFileOpen,
        curBlock.isUnderConstruction() == isFileOpen);
    assertTrue("Block is not in BlocksMap: " + curBlock,
        ns.blockManager.getStoredBlock(curBlock) == curBlock);
  }

  public void testBlockCreation() throws IOException {
    Path file1 = new Path(BASE_DIR, "file1.dat");
    FSDataOutputStream out = TestFileCreation.createFile(hdfs, file1, 3);

    for(int idx = 0; idx < NUM_BLOCKS; idx++) {
      // write one block
      writeFile(file1, out, BLOCK_SIZE);
      // verify consistency
      verifyFileBlocks(file1.toString(), true);
    }

    // close file
    out.close();
    // verify consistency
    verifyFileBlocks(file1.toString(), false);
  }
}