/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.drill.exec.work.batch;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.drill.exec.exception.FragmentSetupException;
import org.apache.drill.exec.physical.base.AbstractPhysicalVisitor;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.physical.base.Receiver;
import org.apache.drill.exec.record.RawFragmentBatch;
import org.apache.drill.exec.rpc.RemoteConnection.ConnectionThrottle;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Determines when a particular fragment has enough data for each of its receiving exchanges to commence execution.  Also monitors whether we've collected all incoming data.
 */
public class IncomingBuffers {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(IncomingBuffers.class);

  private final AtomicInteger streamsRemaining = new AtomicInteger(0);
  private final AtomicInteger remainingRequired = new AtomicInteger(0);
  private final Map<Integer, BatchCollector> fragCounts;

  public IncomingBuffers(PhysicalOperator root) {
    Map<Integer, BatchCollector> counts = Maps.newHashMap();
    CountRequiredFragments reqFrags = new CountRequiredFragments();
    root.accept(reqFrags, counts);
    
    logger.debug("Came up with a list of {} required fragments.  Fragments {}", remainingRequired.get(), counts);
    fragCounts = ImmutableMap.copyOf(counts);

    // Determine the total number of incoming streams that will need to be completed before we are finished.
    int totalStreams = 0;
    for(BatchCollector bc : fragCounts.values()){
      totalStreams += bc.getTotalIncomingFragments();
    }
    assert totalStreams >= remainingRequired.get() : String.format("Total Streams %d should be more than the minimum number of streams to commence (%d).  It isn't.", totalStreams, remainingRequired.get());
    streamsRemaining.set(totalStreams);
  }

  public boolean batchArrived(ConnectionThrottle throttle, RawFragmentBatch batch) throws FragmentSetupException {
    // no need to do anything if we've already enabled running.
    logger.debug("New Batch Arrived {}", batch);
    if(batch.getHeader().getIsLastBatch()){
      streamsRemaining.decrementAndGet();
    }
    int sendMajorFragmentId = batch.getHeader().getSendingMajorFragmentId();
    BatchCollector fSet = fragCounts.get(sendMajorFragmentId);
    if (fSet == null) throw new FragmentSetupException(String.format("We received a major fragment id that we were not expecting.  The id was %d.", sendMajorFragmentId));
    boolean decremented = fSet.batchArrived(throttle, batch.getHeader().getSendingMinorFragmentId(), batch);
    
    // we should only return true if remaining required has been decremented and is currently equal to zero.
    return decremented && remainingRequired.get() == 0;
  }

  public int getRemainingRequired() {
    int rem = remainingRequired.get();
    if (rem < 0) return 0;
    return rem;
  }

  public RawBatchBuffer[] getBuffers(int senderMajorFragmentId){
    return fragCounts.get(senderMajorFragmentId).getBuffers();
  }
  
  
  /**
   * Designed to setup initial values for arriving fragment accounting.
   */
  public class CountRequiredFragments extends AbstractPhysicalVisitor<Void, Map<Integer, BatchCollector>, RuntimeException> {
    
    @Override
    public Void visitReceiver(Receiver receiver, Map<Integer, BatchCollector> counts) throws RuntimeException {
      BatchCollector set;
      if (receiver.supportsOutOfOrderExchange()) {
        set = new MergingCollector(remainingRequired, receiver);
      } else {
        set = new PartitionedCollector(remainingRequired, receiver);
      }
      
      counts.put(set.getOppositeMajorFragmentId(), set);
      remainingRequired.incrementAndGet();
      return null;
    }

    
    @Override
    public Void visitOp(PhysicalOperator op, Map<Integer, BatchCollector> value) throws RuntimeException {
      for(PhysicalOperator o : op){
        o.accept(this, value);
      }
      return null;
    }


  }

  public boolean isDone(){
    return streamsRemaining.get() < 1;
  }
}
