// This file is part of OpenTSDB.
// Copyright (C) 2017  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.query.processor.groupby;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;

import net.openhft.hashing.LongHashFunction;
import net.opentsdb.common.Const;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesByteId;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.data.TimeSeriesStringId;
import net.opentsdb.data.TimeSpecification;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;

/**
 * A result from the {@link GroupBy} node for a segment. The grouping is 
 * performed on the tags specified in the config and then grouped by hash code
 * instead of string on the resulting time series IDs.
 * 
 * @since 3.0
 */
public class GroupByResult implements QueryResult {
  private static final Logger LOG = LoggerFactory.getLogger(GroupByResult.class);
  
  /** Used to denote when all of the upstreams are done with this result set. */
  private final CountDownLatch latch;
  
  /** The parent node. */
  private final GroupBy node;
  
  /** The downstream result received by the group by node. */
  private final QueryResult next;
  
  /** The map of hash codes to groups. */
  private final Map<Long, TimeSeries> groups;
  
  /**
   * The default ctor.
   * @param node The non-null group by node this result belongs to.
   * @param next The non-null original query result.
   * @throws IllegalArgumentException if the node or result was null.
   */
  public GroupByResult(final GroupBy node, final QueryResult next) {
    if (node == null) {
      throw new IllegalArgumentException("Node cannot be null.");
    }
    if (next == null) {
      throw new IllegalArgumentException("Query results cannot be null.");
    }
    latch = new CountDownLatch(node.upstreams());
    this.node = node;
    this.next = next;
    groups = Maps.newHashMap();
    
    if (next.idType().equals(Const.TS_STRING_ID)) {
      for (final TimeSeries series : next.timeSeries()) {
        final TimeSeriesStringId id = (TimeSeriesStringId) series.id();
        final StringBuilder buf = new StringBuilder()
            .append(id.metric());
        
        boolean matched = true;
        for (final String key : ((GroupByConfig) node.config()).getTagKeys()) {
          final String tagv = id.tags().get(key);
          if (tagv == null) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Dropping series from group by due to missing tag key: " 
                  + key + "  " + series.id());
            }
            matched = false;
            break;
          }
          buf.append(tagv);
        }
        
        if (!matched) {
          continue;
        }
        
        final long hash = LongHashFunction.xx_r39().hashChars(buf.toString());
        GroupByTimeSeries group = (GroupByTimeSeries) groups.get(hash);
        if (group == null) {
          group = new GroupByTimeSeries(node);
          groups.put(hash, group);
        }
        group.addSource(series);
      }
    } else if (next.idType().equals(Const.TS_BYTE_ID)) {
      if (((GroupByConfig) node.config()).getEncodedTagKeys() == null) {
        // TODO - proper exception
        throw new RuntimeException("Time series IDs were returned as "
            + "bytes but no encoded tags were provided in the group-by config.");
      }
      for (final TimeSeries series : next.timeSeries()) {
        final TimeSeriesByteId id = (TimeSeriesByteId) series.id();
        // TODO - bench me, may be a better way
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        
        try {
          buf.write(id.metric());
          boolean matched = true;
          for (final byte[] key : ((GroupByConfig) node.config()).getEncodedTagKeys()) {
            if (id.tags() == null || id.tags().size() < 1) {
              matched = false;
              break;
            }
            
            final byte[] tagv = id.tags().get(key);
            if (tagv == null) {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Dropping series from group by due to missing tag key: " 
                    + key + "  " + series.id());
              }
              matched = false;
              break;
            }
            buf.write(tagv);
          }
          
          if (!matched) {
            continue;
          }
          
          final long hash = LongHashFunction.xx_r39().hashBytes(buf.toByteArray());
          GroupByTimeSeries group = (GroupByTimeSeries) groups.get(hash);
          if (group == null) {
            group = new GroupByTimeSeries(node);
            groups.put(hash, group);
          }
          group.addSource(series);
          
        } catch (IOException e) {
          throw new RuntimeException("Failed to build the group-by hash", e);
        }
        
      }
    } else {
      // TODO - proper exception type
      throw new RuntimeException("Unhandled time series ID type: " + next.idType());
    }
  }
  
  @Override
  public TimeSpecification timeSpecification() {
    return next.timeSpecification();
  }

  @Override
  public Collection<TimeSeries> timeSeries() {
    return groups.values();
  }
  
  @Override
  public long sequenceId() {
    return next.sequenceId();
  }
  
  @Override
  public QueryNode source() {
    return node;
  }

  @Override
  public TypeToken<? extends TimeSeriesId> idType() {
    return next.idType();
  }
  
  @Override
  public void close() {
    // NOTE - a race here. Should be idempotent.
    latch.countDown();
    if (latch.getCount() <= 0) {
      next.close();
    }
  }
  
}