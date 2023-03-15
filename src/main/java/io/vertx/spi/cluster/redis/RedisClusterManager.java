/*
 * Copyright (c) 2019 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.spi.cluster.redis;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Counter;
import io.vertx.core.shareddata.Lock;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.core.spi.cluster.NodeInfo;
import io.vertx.core.spi.cluster.NodeListener;
import io.vertx.core.spi.cluster.NodeSelector;
import io.vertx.spi.cluster.redis.Factory.NodeAttachListener;

/**
 * https://github.com/redisson/redisson/wiki/11.-Redis-commands-mapping
 * 
 * 
 * @author <a href="mailto:leo.tu.taipei@gmail.com">Leo Tu</a>
 */
public class RedisClusterManager implements ClusterManager {
  private static final Logger log = LoggerFactory.getLogger(RedisClusterManager.class);

  /**
   * @see io.vertx.core.impl.VertxImpl
   */
  private static final String CLUSTER_MAP_NAME = "__vertx.haInfo"; 
  
  /**
   * @see io.vertx.core.eventbus.impl.clustered.ClusteredEventBus
   */
  private static final String SUBS_MAP_NAME = "__vertx.subs";

  private final Factory factory;

  private VertxInternal vertx;
  private NodeSelector nodeSelector;  
  
  private final RedissonClient redisson;
  private String nodeId;

  private final AtomicBoolean active = new AtomicBoolean();

  private NodeListener nodeListener;
  private Map<String, String> haInfo;
  private AsyncMap<String, NodeInfo> subs;

  private final ConcurrentMap<String, Map<?, ?>> mapCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AsyncMap<?, ?>> asyncMapCache = new ConcurrentHashMap<>();

  public RedisClusterManager(RedissonClient redisson) {
    this(redisson, null, Factory.createDefaultFactory());
  }

  public RedisClusterManager(RedissonClient redisson, String nodeId) {
    this(redisson, nodeId, Factory.createDefaultFactory());
  }

  public RedisClusterManager(RedissonClient redisson, String nodeId, Factory factory) {
    Objects.requireNonNull(redisson, "redisson");
    this.redisson = redisson;
    this.nodeId = nodeId;
    this.factory = factory;
  }


  @Override
  public void init(Vertx vertx, NodeSelector nodeSelector) {
    this.vertx = (VertxInternal) vertx;
    this.nodeSelector = nodeSelector;
  }  
  
  /**
   * EventBus been created !
   */

  @Override
  public <K, V> void getAsyncMap(String name, Promise<AsyncMap<K, V>> promise) {
    if (name.equals(CLUSTER_MAP_NAME)) {
      log.error(MessageFormat.format("name cannot be '{0}'", name));
      promise.fail(new IllegalArgumentException("name cannot be '" + name + "'"));
      return;
    }
    vertx.executeBlocking(future -> {
      @SuppressWarnings("unchecked")
      AsyncMap<K, V> asyncMap = (AsyncMap<K, V>) asyncMapCache.computeIfAbsent(name,
          key -> factory.createAsyncMap(vertx, redisson, name));
      future.complete(asyncMap);
    }, promise);
  }

  /**
   *
   */
  @SuppressWarnings("unchecked")
  @Override
  public <K, V> Map<K, V> getSyncMap(String name) {
    if (name.equals(CLUSTER_MAP_NAME)) {
      synchronized (this) {
        if (haInfo == null) {
          haInfo = factory.createMapHaInfo(vertx, this, redisson, name);
        }
        return (Map<K, V>) haInfo;
      }
    } else {
      Map<K, V> map = (Map<K, V>) mapCache.computeIfAbsent(name, key -> factory.createMap(vertx, redisson, name));
      return map;
    }
  }

  @Override
  public void getLockWithTimeout(String name, long timeout, Handler<AsyncResult<Lock>> resultHandler) {
    try {
      RLock lock = redisson.getLock(name); // getFairLock ?
      lock.tryLockAsync(timeout, TimeUnit.MILLISECONDS).whenComplete((v, e) -> resultHandler
          .handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(new RedisLock(lock))));
    } catch (Exception e) {
      log.info("nodeId: " + nodeId + ", name: " + name + ", timeout: " + timeout, e);
      resultHandler.handle(Future.failedFuture(e));
    }
  }

  @Override
  public void getCounter(String name, Handler<AsyncResult<Counter>> resultHandler) {
    try {
      RAtomicLong counter = redisson.getAtomicLong(name);
      resultHandler.handle(Future.succeededFuture(new RedisCounter(counter)));
    } catch (Exception e) {
      log.info("nodeId: " + nodeId + ", name: " + name, e);
      resultHandler.handle(Future.failedFuture(e));
    }
  }

  @Override
  public String getNodeID() {
    return nodeId;
  }

  /**
   * @see io.vertx.core.impl.HAManager#addHaInfoIfLost
   */
  @Override
  public List<String> getNodes() {
    List<String> nodes = haInfo.keySet().stream().map(e -> e.toString()).collect(Collectors.toList());
    if (nodes.isEmpty()) {
      log.debug("(nodes.isEmpty()), nodeId: {}", nodeId);
    }
    //		else {
    //			log.debug("nodeId: {}, nodes.size: {}, nodes: {}", nodeId, nodes.size(), nodes);
    //		}
    return nodes;
  }

  /**
   * (2)
   * </p>
   * HAManager
   * 
   * @see io.vertx.core.impl.HAManager#nodeAdded
   * @see io.vertx.core.impl.HAManager#nodeLeft
   */
  @Override
  public void nodeListener(NodeListener nodeListener) {
    if (this.nodeListener != null) {
      log.warn("(this.nodeListener != null), nodeId: {}", nodeId);
      throw new IllegalStateException("(this.nodeListener != null), nodeId: " + nodeId);
    }
    this.nodeListener = nodeListener;
    ((NodeAttachListener) this.haInfo).attachListener(this.nodeListener);
  }

  /**
   * (1)
   * <p/>
   * createHaManager
   */
  @Override
  public void join(Handler<AsyncResult<Void>> resultHandler) {
    if (active.compareAndSet(false, true)) {
      this.nodeId = UUID.randomUUID().toString();
      vertx.getOrCreateContext().runOnContext(v -> Future.<Void> succeededFuture().setHandler(resultHandler));
    } else {
      // throw new IllegalStateException("Already activated");
      log.warn("Already activated, nodeId: {}", nodeId);
      vertx.getOrCreateContext().runOnContext(
          v -> Future.<Void> failedFuture(new IllegalStateException("Already activated: " + nodeId))
              .setHandler(resultHandler));
    }
  }

  /**
   *
   */
  @Override
  public void leave(Handler<AsyncResult<Void>> resultHandler) {
    if (active.compareAndSet(true, false)) {
      vertx.getOrCreateContext().runOnContext(v -> Future.<Void> succeededFuture().setHandler(resultHandler));
    } else {
      // throw new IllegalStateException("Already inactive");
      log.warn("Already activated, nodeId: {}", nodeId);
      vertx.getOrCreateContext().runOnContext(
          v -> Future.<Void> failedFuture(new IllegalStateException("Already inactive: " + nodeId))
              .setHandler(resultHandler));
    }
  }

  @Override
  public boolean isActive() {
    return active.get();
  }

  @Override
  public String toString() {
    return super.toString() + "{nodeID=" + getNodeID() + "}";
  }

  /**
   * Lock implement
   */
  private class RedisCounter implements Counter {
    private final RAtomicLong counter;

    public RedisCounter(RAtomicLong counter) {
      this.counter = counter;
    }

    @Override
    public void get(Handler<AsyncResult<Long>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.getAsync().whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }

    @Override
    public void incrementAndGet(Handler<AsyncResult<Long>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.incrementAndGetAsync().whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }

    @Override
    public void getAndIncrement(Handler<AsyncResult<Long>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.getAndIncrementAsync().whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }

    @Override
    public void decrementAndGet(Handler<AsyncResult<Long>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.decrementAndGetAsync().whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }

    @Override
    public void addAndGet(long value, Handler<AsyncResult<Long>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.addAndGetAsync(value).whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }

    @Override
    public void getAndAdd(long value, Handler<AsyncResult<Long>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.getAndAddAsync(value).whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }

    @Override
    public void compareAndSet(long expected, long value, Handler<AsyncResult<Boolean>> resultHandler) {
      Context context = vertx.getOrCreateContext();
      counter.compareAndSetAsync(expected, value).whenComplete((v, e) -> context.runOnContext(vd -> //
      resultHandler.handle(e != null ? Future.failedFuture(e) : Future.succeededFuture(v))) //
      );
    }
  }

  /**
   * Lock implement
   */
  private class RedisLock implements Lock {
    private final RLock lock;

    public RedisLock(RLock lock) {
      this.lock = lock;
    }

    @Override
    public void release() {
      lock.unlock();
    }
  }

}
