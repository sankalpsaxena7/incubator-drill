package org.apache.drill.exec.store;


import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;
import org.apache.drill.exec.store.parquet.ParquetGroupScan;

import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AffinityCalculator {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AffinityCalculator.class);


  BlockLocation[] blocks;
  ImmutableRangeMap<Long,BlockLocation> blockMap;
  FileSystem fs;
  String fileName;
  Collection<DrillbitEndpoint> endpoints;
  HashMap<String,DrillbitEndpoint> endPointMap;
  Stopwatch watch = new Stopwatch();

  public AffinityCalculator(String fileName, FileSystem fs, Collection<DrillbitEndpoint> endpoints) {
    this.fs = fs;
    this.fileName = fileName;
    this.endpoints = endpoints;
    buildBlockMap();
    buildEndpointMap();
  }

  /**
   * Builds a mapping of block locations to file byte range
   */
  private void buildBlockMap() {
    try {
      watch.start();
      FileStatus file = fs.getFileStatus(new Path(fileName));
      blocks = fs.getFileBlockLocations(file, 0 , file.getLen());
      watch.stop();
      logger.debug("Block locations: {}", blocks);
      logger.debug("Took {} ms to get Block locations", watch.elapsed(TimeUnit.MILLISECONDS));
    } catch (IOException ioe) { throw new RuntimeException(ioe); }
    watch.reset();
    watch.start();
    ImmutableRangeMap.Builder<Long, BlockLocation> blockMapBuilder = new ImmutableRangeMap.Builder<Long,BlockLocation>();
    for (BlockLocation block : blocks) {
      long start = block.getOffset();
      long end = start + block.getLength();
      Range<Long> range = Range.closedOpen(start, end);
      blockMapBuilder = blockMapBuilder.put(range, block);
    }
    blockMap = blockMapBuilder.build();
    watch.stop();
    logger.debug("Took {} ms to build block map", watch.elapsed(TimeUnit.MILLISECONDS));
  }
  /**
   * For a given RowGroup, calculate how many bytes are available on each on drillbit endpoint
   *
   * @param rowGroup the RowGroup to calculate endpoint bytes for
   */
  public void setEndpointBytes(ParquetGroupScan.RowGroupInfo rowGroup) {
    watch.reset();
    watch.start();
    HashMap<String,Long> hostMap = new HashMap<>();
    HashMap<DrillbitEndpoint,Long> endpointByteMap = new HashMap();
    long start = rowGroup.getStart();
    long end = start + rowGroup.getLength();
    Range<Long> rowGroupRange = Range.closedOpen(start, end);

    // Find submap of ranges that intersect with the rowGroup
    ImmutableRangeMap<Long,BlockLocation> subRangeMap = blockMap.subRangeMap(rowGroupRange);

    // Iterate through each block in this submap and get the host for the block location
    for (Map.Entry<Range<Long>,BlockLocation> block : subRangeMap.asMapOfRanges().entrySet()) {
      String[] hosts;
      Range<Long> blockRange = block.getKey();
      try {
        hosts = block.getValue().getHosts();
      } catch (IOException ioe) {
        throw new RuntimeException("Failed to get hosts for block location", ioe);
      }
      Range<Long> intersection = rowGroupRange.intersection(blockRange);
      long bytes = intersection.upperEndpoint() - intersection.lowerEndpoint();

      // For each host in the current block location, add the intersecting bytes to the corresponding endpoint
      for (String host : hosts) {
        DrillbitEndpoint endpoint = getDrillBitEndpoint(host);
        if (endpointByteMap.containsKey(endpoint)) {
          endpointByteMap.put(endpoint, endpointByteMap.get(endpoint) + bytes);
        } else {
          if (endpoint != null ) endpointByteMap.put(endpoint, bytes);
        }
      }
    }

    rowGroup.setEndpointBytes(endpointByteMap);
    rowGroup.setMaxBytes(endpointByteMap.size() > 0 ? Collections.max(endpointByteMap.values()) : 0);
    logger.debug("Row group ({},{}) max bytes {}", rowGroup.getPath(), rowGroup.getStart(), rowGroup.getMaxBytes());
    watch.stop();
    logger.debug("Took {} ms to set endpoint bytes", watch.elapsed(TimeUnit.MILLISECONDS));
  }

  private DrillbitEndpoint getDrillBitEndpoint(String hostName) {
    return endPointMap.get(hostName);
  }

  /**
   * Builds a mapping of drillbit endpoints to hostnames
   */
  private void buildEndpointMap() {
    watch.reset();
    watch.start();
    endPointMap = new HashMap<String, DrillbitEndpoint>();
    for (DrillbitEndpoint d : endpoints) {
      String hostName = d.getAddress();
      endPointMap.put(hostName, d);
    }
    watch.stop();
    logger.debug("Took {} ms to build endpoint map", watch.elapsed(TimeUnit.MILLISECONDS));
  }
}
