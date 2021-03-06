package com.basho.riak.ingest.riak;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.timeseries.Store;
import com.basho.riak.client.core.RiakCluster;
import com.basho.riak.client.core.RiakNode;
import com.basho.riak.client.core.query.timeseries.Row;
import com.basho.riak.ingest.config.PipelineConfig;
import com.basho.riak.ingest.kafka.RxKafkaConnector;
import com.gs.collections.impl.list.mutable.FastList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.stereotype.Component;
import rx.functions.Action1;
import rx.subjects.BehaviorSubject;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Component for reacting to string-based messages and parsing them into a {@code List}, turning that into a {@link
 * Row}
 * and inserting that into Riak TS.
 */
@Component
public class RxRiakConnector implements Action1<Row> {

  private static final Logger LOG = LoggerFactory.getLogger(RxRiakConnector.class);

  static final String ERROR_COUNT = "riak.ingest.errorCount";
  static final String MSG_COUNT = "riak.ingest.messageCount";

  private RiakCluster cluster;
  private RiakClient client;

  private final PipelineConfig pipelineConfig;
  private final RxKafkaConnector kafkaConnector;
  private final StringToRowFunction stringToRowFn;
  private final BehaviorSubject<Throwable> errorStream;
  private final CounterService counters;

  private final AtomicBoolean running = new AtomicBoolean(false);

  @Autowired
  public RxRiakConnector(PipelineConfig pipelineConfig,
                         RxKafkaConnector kafkaConnector,
                         StringToRowFunction stringToRowFn,
                         BehaviorSubject<Throwable> errorStream,
                         CounterService counters) {
    this.pipelineConfig = pipelineConfig;
    this.kafkaConnector = kafkaConnector;
    this.stringToRowFn = stringToRowFn;
    this.errorStream = errorStream;
    this.counters = counters;
  }

  @PostConstruct
  public void init() {
    if (LOG.isInfoEnabled()) {
      LOG.info("Connecting to Riak hosts: {}", pipelineConfig.getRiak().getHosts());
    }

    // Transform Set<String> of host:port to a List<RiakNode>
    List<RiakNode> nodes = FastList.newList(pipelineConfig.getRiak().getHosts())
        .collectIf(s -> !s.isEmpty(), s -> s.split(":"))
        .collect(s -> new RiakNode.Builder()
            .withRemoteAddress(s[0])
            .withRemotePort(Integer.valueOf(s[1]))
            .build());

    cluster = RiakCluster.builder(nodes).build();
    client = new RiakClient(cluster);
    if (running.compareAndSet(false, true)) {
      cluster.start();
    }

    kafkaConnector.get()
        .map(msg -> {
          try {
            return stringToRowFn.call(msg);
          } catch (Throwable t) {
            counters.increment(ERROR_COUNT);
            errorStream.onNext(t);
            return new Row();
          }
        })
        .subscribe(this);
  }

  public RiakClient getRiakClient() {
    return client;
  }

  @PreDestroy
  public void cleanup() {
    if (running.compareAndSet(true, false)) {
      cluster.shutdown();
    }
  }

  @Override
  public void call(Row row) {
    if (row.getCellsCopy().isEmpty()) {
      return;
    }
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Storing row to Riak bucket {}", pipelineConfig.getRiak().getBucket());
      }
      client.execute(new Store.Builder(pipelineConfig.getRiak().getBucket())
          .withRow(row)
          .build());

      counters.increment(MSG_COUNT);
      counters.reset(ERROR_COUNT);
    } catch (Exception ex) {
      counters.increment(ERROR_COUNT);
      errorStream.onNext(ex);
    }
  }

}
