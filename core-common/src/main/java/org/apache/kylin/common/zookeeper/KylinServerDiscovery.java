/*
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

package org.apache.kylin.common.zookeeper;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.x.discovery.ServiceCache;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.apache.curator.x.discovery.details.ServiceCacheListener;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.StringUtil;
import org.apache.kylin.common.util.ZKUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class KylinServerDiscovery implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(KylinServerDiscovery.class);

    public static final String SERVICE_PATH = "/service";
    public static final String SERVICE_NAME = "cluster_servers";
    public static final String SERVICE_PAYLOAD_DESCRIPTION = "description";

    private static class SingletonHolder {
        private static final KylinServerDiscovery INSTANCE = new KylinServerDiscovery();
    }

    public static KylinServerDiscovery getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private final KylinConfig kylinConfig;
    private final CuratorFramework curator;
    private final ServiceDiscovery<LinkedHashMap> serviceDiscovery;
    private final ServiceCache<LinkedHashMap> serviceCache;

    private KylinServerDiscovery() {
        this(KylinConfig.getInstanceFromEnv());
    }

    @VisibleForTesting
    protected KylinServerDiscovery(KylinConfig kylinConfig) {
        this.kylinConfig = kylinConfig;
        this.curator = ZKUtil.getZookeeperClient(kylinConfig);
        try {
            final JsonInstanceSerializer<LinkedHashMap> serializer = new JsonInstanceSerializer<>(LinkedHashMap.class);
            serviceDiscovery = ServiceDiscoveryBuilder.builder(LinkedHashMap.class).client(curator)
                    .basePath(SERVICE_PATH).serializer(serializer).build();
            serviceDiscovery.start();

            serviceCache = serviceDiscovery.serviceCacheBuilder().name(SERVICE_NAME)
                    .threadFactory(
                            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("KylinServerTracker-%d").build())
                    .build();

            final AtomicBoolean isFinishInit = new AtomicBoolean(false);
            serviceCache.addListener(new ServiceCacheListener() {
                @Override
                public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                }

                @Override
                public void cacheChanged() {
                    logger.info("Service discovery get cacheChanged notification");
                    final List<ServiceInstance<LinkedHashMap>> instances = serviceCache.getInstances();
                    Map<String, String> instanceNodes = Maps.newHashMapWithExpectedSize(instances.size());
                    for (ServiceInstance<LinkedHashMap> entry : instances) {
                        instanceNodes.put(entry.getAddress() + ":" + entry.getPort(),
                                (String) entry.getPayload().get(SERVICE_PAYLOAD_DESCRIPTION));
                    }

                    logger.info("kylin.server.cluster-servers update to " + instanceNodes);
                    // update cluster servers
                    System.setProperty("kylin.server.cluster-servers", StringUtil.join(instanceNodes.keySet(), ","));

                    // get servers and its mode(query, job, all)
                    final String restServersInClusterWithMode = StringUtil.join(instanceNodes.entrySet().stream()
                            .map(input -> input.getKey() + ":" + input.getValue()).collect(Collectors.toList()), ",");
                    logger.info("kylin.server.cluster-servers-with-mode update to " + restServersInClusterWithMode);
                    System.setProperty("kylin.server.cluster-servers-with-mode", restServersInClusterWithMode);
                    isFinishInit.set(true);
                }
            });
            serviceCache.start();

            registerSelf();
            while (!isFinishInit.get()) {
                logger.info("Haven't registered, waiting ...");
                Thread.sleep(100L);
            }
        } catch (Exception e) {
            throw new RuntimeException("Fail to initialize due to ", e);
        }
    }

    private void registerSelf() throws Exception {
        String hostAddr = kylinConfig.getServerRestAddress();
        String[] hostAddrInfo = hostAddr.split(":");
        if (hostAddrInfo.length < 2) {
            logger.error("kylin.server.host-address {} is not qualified ", hostAddr);
            throw new RuntimeException("kylin.server.host-address " + hostAddr + " is not qualified");
        }
        String host = hostAddrInfo[0];
        int port = Integer.parseInt(hostAddrInfo[1]);

        String serverMode = kylinConfig.getServerMode();
        registerServer(host, port, serverMode);
    }

    private void registerServer(String host, int port, String mode) throws Exception {
        final LinkedHashMap<String, String> instanceDetail = new LinkedHashMap<>();
        instanceDetail.put(SERVICE_PAYLOAD_DESCRIPTION, mode);

        ServiceInstance<LinkedHashMap> thisInstance = ServiceInstance.<LinkedHashMap> builder().name(SERVICE_NAME)
                .payload(instanceDetail).port(port).address(host).build();

        for (ServiceInstance<LinkedHashMap> instance : serviceCache.getInstances()) {
            // Check for registered instances to avoid being double registered
            if (instance.getAddress().equals(thisInstance.getAddress())
                    && instance.getPort().equals(thisInstance.getPort())) {
                serviceDiscovery.unregisterService(instance);
            }
        }
        serviceDiscovery.registerService(thisInstance);
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(serviceCache);
        IOUtils.closeQuietly(serviceDiscovery);
    }
}
