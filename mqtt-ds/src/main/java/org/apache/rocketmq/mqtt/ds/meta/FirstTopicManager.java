/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.mqtt.ds.meta;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.common.protocol.route.QueueData;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;
import org.apache.rocketmq.mqtt.common.facade.MetaPersistManager;
import org.apache.rocketmq.mqtt.ds.config.ServiceConf;
import org.apache.rocketmq.mqtt.ds.mq.MqFactory;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class FirstTopicManager {
    private static Logger logger = LoggerFactory.getLogger(FirstTopicManager.class);

    /**
     * First Topic 存在状态缓存
     */
    private Cache<String, TopicRouteData> topicExistCache;

    /**
     * First Topic 不存在状态缓存
     */
    private Cache<String, Object> topicNotExistCache;
    private DefaultMQAdminExt defaultMQAdminExt;

    /**
     * First Topic 所在 Broker 主节点
     */
    private Map<String /* First Topic */, Map<String, String>> brokerAddressMap = new ConcurrentHashMap<>();

    /**
     * First Topic 有可读队列的 Broker 地址
     */
    private Map<String /* First Topic */, Set<String>> readableBrokers = new ConcurrentHashMap<>();
    private ScheduledThreadPoolExecutor scheduler;

    @Resource
    private ServiceConf serviceConf;

    @Resource
    private MetaPersistManager metaPersistManager;

    @PostConstruct
    public void init() throws MQClientException {
        topicExistCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(1, TimeUnit.MINUTES).build();
        topicNotExistCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(1, TimeUnit.MINUTES).build();
        initMQAdminExt();

        scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactoryImpl("refreshStoreBroker"));
        // 每 5s 更新 Topic 路由信息
        scheduler.scheduleWithFixedDelay(() -> {
            Set<String> copy = new HashSet<>();
            copy.add(serviceConf.getClientRetryTopic());
            copy.add(serviceConf.getClientP2pTopic());
            Set<String> allFirstTopics = metaPersistManager.getAllFirstTopics();
            if (allFirstTopics != null) {
                copy.addAll(allFirstTopics);
            }
            for (String firstTopic : copy) {
                updateTopicRoute(firstTopic);
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    public void initMQAdminExt() throws MQClientException {
        defaultMQAdminExt = MqFactory.buildDefaultMQAdminExt("TopicCheck", serviceConf.getProperties());
        defaultMQAdminExt.start();
    }

    /**
     * 检查 First Topic 是路由信息，并更新缓存
     *
     * @param firstTopic
     */
    public void checkFirstTopicIfCreated(String firstTopic) {
        if (topicExistCache.getIfPresent(firstTopic) != null) {
            return;
        }
        if (topicNotExistCache.getIfPresent(firstTopic) != null) {
            throw new TopicNotExistException(firstTopic + " NotExist");
        }
        try {
            // 尝试查询 First Topic 元数据
            TopicRouteData topicRouteData = defaultMQAdminExt.examineTopicRouteInfo(firstTopic);
            // 如果元数据不存在，缓存不存在的 Topic 信息
            if (topicRouteData == null || topicRouteData.getBrokerDatas() == null || topicRouteData.getBrokerDatas().isEmpty()) {
                topicNotExistCache.put(firstTopic, new Object());
                throw new TopicNotExistException(firstTopic + " NotExist");
            }
            // 如果存在，缓存 Topic 路由信息
            updateTopicRoute(firstTopic, topicRouteData);
            topicExistCache.put(firstTopic, topicRouteData);
        } catch (MQClientException e) {
            if (ResponseCode.TOPIC_NOT_EXIST == e.getResponseCode()) {
                topicNotExistCache.put(firstTopic, new Object());
                throw new TopicNotExistException(firstTopic + " NotExist");
            }
        } catch (Exception e) {
            logger.error("check topic {} exception", firstTopic, e);
        }
    }

    /**
     * 更新 Topic 路由信息
     *
     * @param firstTopic
     */
    private void updateTopicRoute(String firstTopic) {
        if (StringUtils.isBlank(firstTopic)) {
            return;
        }
        try {
            TopicRouteData topicRouteData = defaultMQAdminExt.examineTopicRouteInfo(firstTopic);
            updateTopicRoute(firstTopic, topicRouteData);
        } catch (MQClientException t) {
            if (t.getResponseCode() == ResponseCode.TOPIC_NOT_EXIST) {
                brokerAddressMap.remove(firstTopic);
                readableBrokers.remove(firstTopic);
            }
        } catch (Throwable throwable) {
            logger.error("", throwable);
        }
    }

    /**
     * 更新 First Topic 路由信息
     *
     * @param firstTopic
     * @param topicRouteData
     */
    private void updateTopicRoute(String firstTopic, TopicRouteData topicRouteData) {
        if (topicRouteData == null || firstTopic == null) {
            return;
        }
        Map<String, String> tmp = new ConcurrentHashMap<>();
        for (BrokerData brokerData : topicRouteData.getBrokerDatas()) {
            tmp.put(brokerData.getBrokerName(), brokerData.getBrokerAddrs().get(MixAll.MASTER_ID));
        }
        brokerAddressMap.put(firstTopic, tmp);
        Set<String> tmpBrokers = new HashSet<>();
        for (QueueData qd : topicRouteData.getQueueDatas()) {
            if (PermName.isReadable(qd.getPerm())) {
                tmpBrokers.add(qd.getBrokerName());
            }
        }
        readableBrokers.put(firstTopic, tmpBrokers);
    }

    public Map<String, String> getBrokerAddressMap(String firstTopic) {
        Map<String, String> copy = new ConcurrentHashMap<>();
        Map<String, String> map = brokerAddressMap.get(firstTopic);
        if (map != null) {
            copy.putAll(map);
        }
        return copy;
    }

    public Set<String> getReadableBrokers(String firstTopic) {
        Set<String> copy = new HashSet<>();
        Set<String> set = readableBrokers.get(firstTopic);
        if (set != null) {
            copy.addAll(set);
        }
        return copy;
    }

}
