/*
 * Copyright (c) 2018 The original author or authors
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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.test.core.AsyncTestBase;

/**
 * 
 * @author <a href="mailto:leo.tu.taipei@gmail.com">Leo Tu</a>
 */
@SuppressWarnings("deprecation")
public class RetriableSenderTest extends AsyncTestBase {
  private static final Logger log = LoggerFactory.getLogger(RetriableSenderTest.class);

  static protected RedissonClient createRedissonClient() {
    log.debug("...");
    Config config = new Config();
    config.useSingleServer() //
        .setAddress("redis://127.0.0.1:6379") //
        .setDatabase(2) //
        .setPassword("mypwd") //
        .setTcpNoDelay(true) //
        .setKeepAlive(true) //
        .setConnectionPoolSize(128) //
        .setTimeout(1000 * 10) //
        .setConnectionMinimumIdleSize(5);
    return Redisson.create(config);
  }

  static protected void closeRedissonClient(RedissonClient redisson) {
    redisson.shutdown(3, 15, TimeUnit.SECONDS);
    log.debug("after shutdown");
  }

  @Test
  public void test1EventBusP2P() throws Exception {
    log.debug("BEGIN...");

    String clusterHost2 = IpUtil.getLocalRealIP();
    int clusterPort2 = 8080;

    RedissonClient redisson2 = createRedissonClient();

    RedisClusterManager mgr2 = new RedisClusterManager(redisson2, clusterHost2 + "_" + clusterPort2);

    VertxOptions options2 = new VertxOptions().setClusterManager(mgr2);
    options2.getEventBusOptions().setHost(clusterHost2).setPort(clusterPort2);
    options2.setInternalBlockingPoolSize(VertxOptions.DEFAULT_INTERNAL_BLOCKING_POOL_SIZE * 2)
        .setWorkerPoolSize(VertxOptions.DEFAULT_WORKER_POOL_SIZE * 2);

    AtomicReference<Vertx> vertx2 = new AtomicReference<>();

    int maxCount = 57_000; // raise error: 60_000; ok: 57_000
    //		AtomicInteger counter = new AtomicInteger(maxCount);

    // Producer
    Vertx.clusteredVertx(options2, res -> {
      assertTrue(res.succeeded());
      assertNotNull(mgr2.getNodeId());
      vertx2.set(res.result());
    });

    assertWaitUntil(() -> vertx2.get() != null);

    sleep("Ready for clusters initialize");

    AtomicInteger replyCountdown = new AtomicInteger(maxCount);
    Vertx vertx = vertx2.get();
    String address = "Retriable";

    log.debug("send...");
    AtomicReference<Throwable> error = new AtomicReference<>();
    new Thread(() -> {
      for (int i = 0; i < maxCount && error.get() == null; i++) {
        if (i % 500 == 0) {
          log.debug("{}, send message", i);
          sleep("send: " + i, 1000);
        }
        sleep("send:" + i, 50);
        vertx.eventBus().<String>request(address, "hello:" + i, ar -> {
          if (replyCountdown.get() % 1000 == 0) {
            log.debug("{}, reply message", replyCountdown);
          }
          if (replyCountdown.decrementAndGet() == 0) {
            log.info("Reply count down completed");
            testComplete(); // XXX
          }

          if (ar.succeeded()) {
            // log.debug("reply succeeded: {}", ar.result().body());
            assertTrue(ar.result().body().startsWith("ok:"));
          } else {
            log.warn("reply failed: {}", ar.cause().toString());
            error.set(ar.cause());
            try {
              testComplete(); // XXX
            } catch (Exception e) {
              // ignored
            }
          }
        });
      }
    }).start();

    log.debug("await...");
    await(10, TimeUnit.MINUTES); // XXX

    log.debug("close...");
    Promise<Void> f2 = Promise.promise();
    vertx2.get().close(f2);

    //
    log.debug("finish...");
    CountDownLatch finish = new CountDownLatch(1);
    f2.future().onComplete(ar -> {
      finish.countDown();
    });

    finish.await(1, TimeUnit.MINUTES);
    sleep("END Before return, error: " + error.get());

    closeRedissonClient(redisson2);
  }

  private void sleep(String msg) {
    log.debug("Sleep: {}", msg);
    try {
      Thread.sleep(1000 * 3);
    } catch (InterruptedException e) {
      log.warn(e.toString());
    }
  }

  private void sleep(String msg, long millis) {
    // log.debug("Sleep: {}", msg);
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      log.warn(e.toString());
    }
  }
}
