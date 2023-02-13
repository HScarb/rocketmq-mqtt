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

package org.apache.rocketmq.mqtt.ds.notify;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.mqtt.common.facade.MetaPersistManager;
import org.apache.rocketmq.mqtt.common.model.Constants;
import org.apache.rocketmq.mqtt.common.model.MessageEvent;
import org.apache.rocketmq.mqtt.common.model.RpcCode;
import org.apache.rocketmq.mqtt.common.util.TopicUtils;
import org.apache.rocketmq.mqtt.ds.config.ServiceConf;
import org.apache.rocketmq.mqtt.ds.meta.FirstTopicManager;
import org.apache.rocketmq.mqtt.ds.meta.TopicNotExistException;
import org.apache.rocketmq.mqtt.ds.mq.MqFactory;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyRemotingClient;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 新消息通知管理器
 * 消费消息，当有新消息可以消费时，发送通知
 * @see <a href="https://docs.google.com/document/d/1AD1GkV9mqE_YFA97uVem4SmB8ZJSXiJZvzt7-K6Jons/edit#heading=h.tz9wsreyzs2e">Push-Pull Model</a>
 */
@Component
public class NotifyManager {
    private static Logger logger = LoggerFactory.getLogger(NotifyManager.class);
    private DefaultMQPushConsumer defaultMQPushConsumer;
    private String dispatcherConsumerGroup = MixAll.CID_RMQ_SYS_PREFIX + "mqtt_event";
    private ScheduledThreadPoolExecutor scheduler;
    private Set<String> topics = new HashSet<>();
    private Map<String, AtomicInteger> nodeFail = new ConcurrentHashMap<>();
    private static final int NODE_FAIL_MAX_NUM = 3;
    private NettyRemotingClient remotingClient;
    private DefaultMQProducer defaultMQProducer;


    @Resource
    private ServiceConf serviceConf;

    @Resource
    private MetaPersistManager metaPersistManager;

    @Resource
    private FirstTopicManager firstTopicManager;

    @PostConstruct
    public void init() throws MQClientException {
        defaultMQPushConsumer = MqFactory.buildDefaultMQPushConsumer(dispatcherConsumerGroup, serviceConf.getProperties(), new Dispatcher());
        defaultMQPushConsumer.setPullInterval(1);
        defaultMQPushConsumer.setConsumeMessageBatchMaxSize(64);
        defaultMQPushConsumer.setPullBatchSize(32);
        defaultMQPushConsumer.setConsumeThreadMin(32);
        defaultMQPushConsumer.setConsumeThreadMax(64);

        defaultMQProducer = MqFactory.buildDefaultMQProducer(MixAll.CID_RMQ_SYS_PREFIX + "NotifyRetrySend", serviceConf.getProperties());

        try {
            defaultMQPushConsumer.start();
            defaultMQProducer.start();
        } catch (Exception e) {
            logger.error("", e);
        }

        scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactoryImpl("Refresh_Notify_Topic_"));
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (Exception e) {
                logger.error("", e);
            }
        }, 0, 5, TimeUnit.SECONDS);

        NettyClientConfig config = new NettyClientConfig();
        remotingClient = new NettyRemotingClient(config);
        remotingClient.start();
    }

    /**
     * 刷新订阅的 Topic
     *
     * @throws MQClientException
     */
    private void refresh() throws MQClientException {
        // 从元数据存储中获取所有 First Topic
        Set<String> tmp = metaPersistManager.getAllFirstTopics();
        if (tmp == null || tmp.isEmpty()) {
            return;
        }
        Set<String> thisTopicList = new HashSet<>();
        for (String topic : tmp) {
            try {
                if (topic.equals(serviceConf.getClientRetryTopic())) {
                    // notify by RetryDriver self
                    continue;
                }
                // 查询 Topic 路由信息，更新缓存
                firstTopicManager.checkFirstTopicIfCreated(topic);
                thisTopicList.add(topic);
                // 如果有新的 Topic 被订阅，在消费者添加订阅
                if (!topics.contains(topic)) {
                    // 消费者订阅新的 Topic
                    subscribe(topic);
                    topics.add(topic);
                }
            } catch (TopicNotExistException e) {
                logger.error("", e);
            }
        }
        // 取消订阅被从元数据中删除的 Topic
        Iterator<String> iterator = topics.iterator();
        while (iterator.hasNext()) {
            String topic = iterator.next();
            if (!thisTopicList.contains(topic)) {
                iterator.remove();
                unsubscribe(topic);
            }
        }
    }

    /**
     * 消费者订阅新的 Topic
     *
     * @param topic
     * @throws MQClientException
     */
    private void subscribe(String topic) throws MQClientException {
        defaultMQPushConsumer.subscribe(topic, "*");
        logger.warn("subscribe:{}", topic);
    }

    /**
     * 取消订阅从元数据中删除的 Topic
     *
     * @param topic
     */
    private void unsubscribe(String topic) {
        try {
            logger.warn("unsubscribe:{}", topic);
            defaultMQPushConsumer.unsubscribe(topic);
            defaultMQPushConsumer.getDefaultMQPushConsumerImpl().getRebalanceImpl().getTopicSubscribeInfoTable().remove(topic);
            defaultMQPushConsumer.getDefaultMQPushConsumerImpl().getmQClientFactory().getDefaultMQProducer()
                    .getDefaultMQProducerImpl().getTopicPublishInfoTable().remove(topic);
        } catch (Exception e) {
            logger.error("{}", topic, e);
        }
    }

    class Dispatcher implements MessageListenerConcurrently {

        @Override
        public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
            try {
                Set<MessageEvent> messageEvents = new HashSet<>();
                for (MessageExt message : msgs) {
                    MessageEvent messageEvent = new MessageEvent();
                    messageEvent.setBrokerName(context.getMessageQueue().getBrokerName());
                    setPubTopic(messageEvent, message);
                    String namespace = message.getUserProperty(Constants.PROPERTY_NAMESPACE);
                    messageEvent.setNamespace(namespace);
                    messageEvents.add(messageEvent);
                }
                notifyMessage(messageEvents);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception e) {
                logger.error("", e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
    }

    private void setPubTopic(MessageEvent messageEvent, MessageExt message) {
        if (StringUtils.isNotBlank(message.getUserProperty(Constants.PROPERTY_ORIGIN_MQTT_TOPIC))) {
            // from mqtt
            messageEvent.setPubTopic(message.getUserProperty(Constants.PROPERTY_ORIGIN_MQTT_TOPIC));
            return;
        }
        if (StringUtils.isNotBlank(message.getUserProperty(MessageConst.PROPERTY_INNER_MULTI_DISPATCH))) {
            // maybe from rmq
            String s = message.getUserProperty(MessageConst.PROPERTY_INNER_MULTI_DISPATCH);
            String[] lmqSet = s.split(MixAll.MULTI_DISPATCH_QUEUE_SPLITTER);
            for (String lmq : lmqSet) {
                if (TopicUtils.isWildCard(lmq)) {
                    continue;
                }
                if (!lmq.contains(MixAll.LMQ_PREFIX)) {
                    continue;
                }
                String originQueue = lmq.replace(MixAll.LMQ_PREFIX, "");
                messageEvent.setPubTopic(StringUtils.replace(originQueue, "%","/"));
            }
        }
    }

    /**
     * 发送请求到所有 MQTT Proxy 节点，通知新消息到达
     *
     * @param messageEvents 所有消费到的新消息
     * @throws MQBrokerException
     * @throws RemotingException
     * @throws InterruptedException
     * @throws MQClientException
     */
    public void notifyMessage(Set<MessageEvent> messageEvents) throws
            MQBrokerException, RemotingException, InterruptedException, MQClientException {
        Set<String> connectorNodes = metaPersistManager.getConnectNodeSet();
        if (connectorNodes == null || connectorNodes.isEmpty()) {
            throw new RemotingException("No Connect Nodes");
        }
        for (String node : connectorNodes) {
            boolean result = false;
            try {
                AtomicInteger nodeFailCount = nodeFail.get(node);
                if (nodeFailCount == null) {
                    nodeFailCount = new AtomicInteger();
                    AtomicInteger old = nodeFail.putIfAbsent(node, nodeFailCount);
                    if (old != null) {
                        nodeFailCount = old;
                    }
                }
                if (nodeFailCount.get() > NODE_FAIL_MAX_NUM) {
                    sendEventRetryMsg(messageEvents, 1, node, 0);
                    continue;
                }
                if (result = doNotify(node, messageEvents)) {
                    nodeFailCount.set(0);
                    continue;
                }
                nodeFailCount.incrementAndGet();
            } catch (Exception e) {
                logger.error("", e);
                result = false;
            } finally {
                if (!result) {
                    sendEventRetryMsg(messageEvents, 1, node, 0);
                }
            }
        }
    }

    /**
     * 发送 CMD_NOTIFY_MQTT_MESSAGE 请求到 MQTT Proxy 节点，通知新消息到达
     *
     * @param node MQTT Proxy 节点 RPC 协议地址
     * @param messageEvents 消费到的新消息
     * @return 请求发送是否成功
     */
    protected boolean doNotify(String node, Set<MessageEvent> messageEvents) {
        Set<String> connectorNodes = metaPersistManager.getConnectNodeSet();
        if (connectorNodes == null || connectorNodes.isEmpty()) {
            return false;
        }
        if (!connectorNodes.contains(node)) {
            return true;
        }
        try {
            RemotingCommand eventCommand = createMsgEventCommand(messageEvents);
            RemotingCommand response = remotingClient.invokeSync(node + ":" + serviceConf.getCsRpcPort(), eventCommand, 1000);
            return response.getCode() == RpcCode.SUCCESS;
        } catch (Exception e) {
            logger.error("fail notify {}", node, e);
            return false;
        }
    }

    private RemotingCommand createMsgEventCommand(Set<MessageEvent> messageEvents) {
        RemotingCommand remotingCommand = RemotingCommand.createRequestCommand(RpcCode.CMD_NOTIFY_MQTT_MESSAGE,
                null);
        remotingCommand.setBody(JSON.toJSONBytes(messageEvents));
        return remotingCommand;
    }

    protected void sendEventRetryMsg(Set<MessageEvent> messageEvents, int delayLevel, String node, int retryTime)
            throws InterruptedException, RemotingException, MQClientException,
            MQBrokerException {
        if (retryTime >= serviceConf.getEventNotifyRetryMaxTime()) {
            return;
        }
        Message message = new Message();
        message.setTopic(serviceConf.getEventNotifyRetryTopic());
        message.setBody(JSON.toJSONBytes(messageEvents));
        message.setDelayTimeLevel(delayLevel);
        message.putUserProperty(Constants.PROPERTY_MQTT_MSG_EVENT_RETRY_NODE, node);
        message.putUserProperty(Constants.PROPERTY_MQTT_MSG_EVENT_RETRY_TIME, String.valueOf(retryTime + 1));
        defaultMQProducer.send(message);
    }

}
