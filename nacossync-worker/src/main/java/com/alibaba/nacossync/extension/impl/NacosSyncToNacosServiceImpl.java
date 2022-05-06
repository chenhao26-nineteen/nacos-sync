/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.nacossync.extension.impl;

import static com.alibaba.nacossync.util.NacosUtils.getGroupNameOrDefault;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacossync.cache.SkyWalkerCacheServices;
import com.alibaba.nacossync.constant.ClusterTypeEnum;
import com.alibaba.nacossync.constant.MetricsStatisticsType;
import com.alibaba.nacossync.constant.SkyWalkerConstants;
import com.alibaba.nacossync.extension.SyncService;
import com.alibaba.nacossync.extension.annotation.NacosSyncService;
import com.alibaba.nacossync.extension.holder.NacosServerHolder;
import com.alibaba.nacossync.monitor.MetricsManager;
import com.alibaba.nacossync.pojo.model.TaskDO;
import com.alibaba.nacossync.util.Collections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;

import com.alibaba.nacossync.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author yangyshdan
 * @version $Id: ConfigServerSyncManagerService.java, v 0.1 2018-11-12 下午5:17 NacosSync Exp $$
 */

@Slf4j
@NacosSyncService(sourceCluster = ClusterTypeEnum.NACOS, destinationCluster = ClusterTypeEnum.NACOS)
public class NacosSyncToNacosServiceImpl implements SyncService {

    private Map<String, EventListener> listenerMap = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> sourceInstanceSnapshot = new ConcurrentHashMap<>();

    private final Map<String, Integer> syncTaskTap = new ConcurrentHashMap<>();

    @Autowired
    private MetricsManager metricsManager;

    @Autowired
    private SkyWalkerCacheServices skyWalkerCacheServices;

    @Autowired
    private NacosServerHolder nacosServerHolder;

    private ConcurrentHashMap<String, TaskDO> allSyncTaskMap = new ConcurrentHashMap<String, TaskDO>();

    /**
     * 因为网络故障等原因，nacos sync的同步任务会失败，导致目标集群注册中心缺少同步实例， 为避免目标集群注册中心长时间缺少同步实例，每隔5分钟启动一个兜底工作线程执行一遍全部的同步任务。
     */
    @PostConstruct
    public void startBasicSyncTaskThread() {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("com.alibaba.nacossync.basic.synctask");
            return t;
        });

        executorService.scheduleWithFixedDelay(() -> {
            if (allSyncTaskMap.size() == 0) {
                return;
            }

            try {
                for (TaskDO taskDO : allSyncTaskMap.values()) {
                    String taskId = taskDO.getTaskId();
                    NamingService sourceNamingService =
                        nacosServerHolder.get(taskDO.getSourceClusterId());
                    String key = taskDO.getSourceClusterId() + ":" + taskDO.getDestClusterId();
                    nacosServerHolder.get(key);
                    NamingService destNamingService = nacosServerHolder.getNameService(key);
                    try {
                        doSync(taskId, taskDO, sourceNamingService, destNamingService);
                    } catch (Exception e) {
                        log.error("basic synctask process fail, taskId:{}", taskId, e);
                        metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
                    }
                }
            } catch (Throwable e) {
                log.warn("basic synctask thread error", e);
            }
        }, 0, 300, TimeUnit.SECONDS);
    }

    @Override
    public boolean delete(TaskDO taskDO) {
        try {
            NamingService sourceNamingService =
                nacosServerHolder.get(taskDO.getSourceClusterId());
            String key = taskDO.getSourceClusterId() + ":" + taskDO.getDestClusterId();
            nacosServerHolder.get(key);
            NamingService destNamingService = nacosServerHolder.getNameService(key);
            //移除订阅
            sourceNamingService
                .unsubscribe(taskDO.getServiceName(), getGroupNameOrDefault(taskDO.getGroupName()),
                    listenerMap.remove(taskDO.getTaskId()));
            sourceInstanceSnapshot.remove(taskDO.getTaskId());
            allSyncTaskMap.remove(taskDO.getTaskId());

            // 删除目标集群中同步的实例列表
            List<Instance> sourceInstances = sourceNamingService
                .getAllInstances(taskDO.getServiceName(), getGroupNameOrDefault(taskDO.getGroupName()),
                    new ArrayList<>(), false);
            for (Instance instance : sourceInstances) {
                if (needSync(instance.getMetadata())) {
                    destNamingService
                        .deregisterInstance(taskDO.getServiceName(), getGroupNameOrDefault(taskDO.getGroupName()),
                            instance.getIp(),
                            instance.getPort());
                }
            }
        } catch (Exception e) {
            log.error("delete task from nacos to nacos was failed, taskId:{}", taskDO.getTaskId(), e);
            metricsManager.recordError(MetricsStatisticsType.DELETE_ERROR);
            return false;
        }
        return true;
    }

    @Override
    public synchronized boolean  sync(TaskDO taskDO) {
        String taskId = taskDO.getTaskId();
        try {
            NamingService sourceNamingService =
                nacosServerHolder.get(taskDO.getSourceClusterId());
            String key = taskDO.getSourceClusterId() + ":" + taskDO.getDestClusterId();
            nacosServerHolder.get(key);
            NamingService destNamingService = nacosServerHolder.getNameService(key);
            allSyncTaskMap.put(taskId, taskDO);
            //防止暂停同步任务后,重新同步/或删除任务以后新建任务不会再接收到新的事件导致不能同步,所以每次订阅事件之前,先全量同步一次任务
            doSync(taskId, taskDO, sourceNamingService, destNamingService);
            this.listenerMap.putIfAbsent(taskId, event -> {
                if (event instanceof NamingEvent) {
                    try {
                        doSync(taskId, taskDO, sourceNamingService, destNamingService);
                    } catch (Exception e) {
                        log.error("event process fail, taskId:{}", taskId, e);
                        metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
                    }
                }
            });
            sourceNamingService.subscribe(taskDO.getServiceName(), getGroupNameOrDefault(taskDO.getGroupName()),
                listenerMap.get(taskId));
        } catch (Exception e) {
            log.error("sync task from nacos to nacos was failed, taskId:{}", taskId, e);
            metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
            return false;
        }
        return true;
    }

    private void doSync(String taskId, TaskDO taskDO, NamingService sourceNamingService,
        NamingService destNamingService) throws NacosException {
        if (syncTaskTap.putIfAbsent(taskId, 1) != null) {
            log.info("任务Id:{}上一个同步任务尚未结束", taskId);
            return;
        }
        try {
            // 直接从本地保存的serviceInfoMap中取订阅的服务实例
            List<Instance> sourceInstances = sourceNamingService.getAllInstances(taskDO.getServiceName(),
                getGroupNameOrDefault(taskDO.getGroupName()), new ArrayList<>(), true);
            // processing instance offline
            if (processDeRegisterInstances(taskDO, destNamingService, sourceInstances)) {
                return;
            }
            // Synchronization instance
            this.syncNewInstance(taskDO, destNamingService, sourceInstances);
        } finally {
            syncTaskTap.remove(taskId);
        }
    }
    
    private boolean processDeRegisterInstances(TaskDO taskDO, NamingService destNamingService, List<Instance> sourceInstances)
            throws NacosException {
        //如果此时sourceInstance中的实例为空，证明此时实例下线或实例不存在
        if (CollectionUtils.isEmpty(sourceInstances)) {
            List<Instance> destInstances = destNamingService.getAllInstances(taskDO.getServiceName(),
                    getGroupNameOrDefault(taskDO.getGroupName()), new ArrayList<>(), true);
            if (CollectionUtils.isEmpty(destInstances)) {
                // 不需要执行反注册
                return true;
            }
            deRegisterFilter(destInstances,taskDO.getSourceClusterId());
            if (CollectionUtils.isNotEmpty(destInstances)){
                //执行反注册,拿出一个实例即可
                destNamingService.deregisterInstance(taskDO.getServiceName(),getGroupNameOrDefault(taskDO.getGroupName()),destInstances.get(0));
            }
            return true;
        }
        return false;
    }

    private void deRegisterFilter(List<Instance> destInstances, String sourceClusterId){
        List<Instance> newDestInstance = new ArrayList<>();
        for (Instance destInstance : destInstances) {
            Map<String, String> metadata = destInstance.getMetadata();
            String destSourceClusterId = metadata.get(SkyWalkerConstants.SOURCE_CLUSTERID_KEY);
            if (needDeregister(destSourceClusterId, sourceClusterId)) {
                // 需要执行反注册
                newDestInstance.add(destInstance);
            }
        }
        destInstances = newDestInstance;
    }
    
    private static boolean needDeregister(String destClusterId, String sourceClusterId){
        if (!StringUtils.isEmpty(destClusterId)) {
            return destClusterId.equals(sourceClusterId);
        }
        return false;
    }

    private void syncNewInstance(TaskDO taskDO, NamingService destNamingService,
        List<Instance> sourceInstances) throws NacosException {
        Set<String> latestSyncInstance = new TreeSet<>();
        //再次添加新实例
        String taskId = taskDO.getTaskId();
        List<Instance> needBatchRegisterInstances = new ArrayList<>();
        for (Instance instance : sourceInstances) {
            if (needSync(instance.getMetadata())) {
                needBatchRegisterInstances.add(buildSyncInstance(instance,taskDO));
                latestSyncInstance.add(instance.toString());
            }
        }
        
        if (CollectionUtils.isNotEmpty(needBatchRegisterInstances)) {
            destNamingService.batchRegisterInstance(taskDO.getServiceName(), getGroupNameOrDefault(taskDO.getGroupName()),
                    needBatchRegisterInstances);
        }
        if (CollectionUtils.isNotEmpty(latestSyncInstance)) {

            log.info("任务Id:{},已同步实例个数:{}", taskId, latestSyncInstance.size());
            sourceInstanceSnapshot.put(taskId, latestSyncInstance);
        }
    }

    private Instance buildSyncInstance(Instance instance, TaskDO taskDO) {
        Instance temp = new Instance();
        temp.setIp(instance.getIp());
        temp.setPort(instance.getPort());
        temp.setClusterName(instance.getClusterName());
        temp.setServiceName(instance.getServiceName());
        temp.setEnabled(instance.isEnabled());
        temp.setHealthy(instance.isHealthy());
        temp.setWeight(instance.getWeight());
        temp.setEphemeral(instance.isEphemeral());
        Map<String, String> metaData = new HashMap<>();
        metaData.putAll(instance.getMetadata());
        metaData.put(SkyWalkerConstants.DEST_CLUSTERID_KEY, taskDO.getDestClusterId());
        metaData.put(SkyWalkerConstants.SYNC_SOURCE_KEY,
            skyWalkerCacheServices.getClusterType(taskDO.getSourceClusterId()).getCode());
        metaData.put(SkyWalkerConstants.SOURCE_CLUSTERID_KEY, taskDO.getSourceClusterId());
        //标识是同步实例
        metaData.put(SkyWalkerConstants.SYNC_INSTANCE_TAG, taskDO.getSourceClusterId()+"@@"+taskDO.getVersion());
        temp.setMetadata(metaData);
        return temp;
    }


}
