package com.alibaba.rocketmq.storm.trident;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import storm.trident.operation.TridentCollector;
import storm.trident.spout.IPartitionedTridentSpout;
import storm.trident.spout.ISpoutPartition;
import storm.trident.topology.TransactionAttempt;
import backtype.storm.task.TopologyContext;
import backtype.storm.tuple.Fields;

import com.alibaba.rocketmq.client.consumer.PullResult;
import com.alibaba.rocketmq.client.exception.MQBrokerException;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.remoting.exception.RemotingException;
import com.alibaba.rocketmq.storm.MessagePullConsumer;
import com.alibaba.rocketmq.storm.domain.BatchMessage;
import com.alibaba.rocketmq.storm.domain.RocketMQConfig;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;

/**
 * @author Von Gosling
 */
public class RocketMQTridentSpout implements
        IPartitionedTridentSpout<List<MessageQueue>, ISpoutPartition, BatchMessage> {

    private static final long                                      serialVersionUID   = 8972193358178718167L;

    private static final Logger                                    LOG                = LoggerFactory
                                                                                              .getLogger(RocketMQTridentSpout.class);

    private static final ConcurrentMap<String, List<MessageQueue>> cachedMessageQueue = new MapMaker()
                                                                                              .makeMap();
    private RocketMQConfig                                         config;
    private MessagePullConsumer                                    consumer;

    public RocketMQTridentSpout() {
    }

    public RocketMQTridentSpout(RocketMQConfig config) throws MQClientException {
        this.config = config;
        try {
            consumer = new MessagePullConsumer(config);
            Set<MessageQueue> mqs = consumer.getConsumer().fetchSubscribeMessageQueues(
                    config.getTopic());

            consumer.getTopicQueueMappings().put(config.getTopic(), Lists.newArrayList(mqs));
        } catch (Exception e) {
            LOG.error("Error occured !", e);
            throw new IllegalStateException(e);
        }

    }

    private List<MessageQueue> getMessageQueue(String topic) throws MQClientException {
        List<MessageQueue> cachedQueue = Lists.newArrayList();
        cachedQueue = consumer.getTopicQueueMappings().get(config.getTopic());
        if (cachedQueue == null) {
            Set<MessageQueue> mqs = consumer.getConsumer().fetchSubscribeMessageQueues(
                    config.getTopic());
            cachedQueue = Lists.newArrayList(mqs);
            cachedMessageQueue.put(config.getTopic(), cachedQueue);
        }
        return cachedQueue;
    }

    class RocketMQCoordinator implements Coordinator<List<MessageQueue>> {

        @Override
        public List<MessageQueue> getPartitionsForBatch() {
            List<MessageQueue> queues = Lists.newArrayList();
            try {
                queues = getMessageQueue(config.getTopic());
            } catch (Exception e) {
                e.printStackTrace();
            }
            return queues;
        }

        @Override
        public boolean isReady(long txid) {
            return true;
        }

        @Override
        public void close() {
            LOG.info("close coordinator!");
        }

    }

    class RocketMQEmitter implements Emitter<List<MessageQueue>, ISpoutPartition, BatchMessage> {

        @Override
        public List<ISpoutPartition> getOrderedPartitions(List<MessageQueue> allPartitionInfo) {
            List<ISpoutPartition> partition = Lists.newArrayList();
            for (final MessageQueue queue : allPartitionInfo) {
                partition.add(new ISpoutPartition() {
                    @Override
                    public String getId() {
                        return String.valueOf(queue.getQueueId());
                    }
                });
            }
            return partition;
        }

        @Override
        public BatchMessage emitPartitionBatchNew(TransactionAttempt tx,
                                                  TridentCollector collector,
                                                  ISpoutPartition partition,
                                                  BatchMessage lastPartitionMeta) {
            long index = 0;
            BatchMessage batchMessages = null;
            MessageQueue mq = null;
            try {
                if (lastPartitionMeta == null) {
                    index = consumer.getConsumer().fetchConsumeOffset(mq, true);
                    index = index == -1 ? 0 : index;
                } else {
                    index = lastPartitionMeta.getNextOffset();
                }

                mq = getMessageQueue(config.getTopic()).get(Integer.parseInt(partition.getId()));

                PullResult result = consumer.getConsumer().pullBlockIfNotFound(mq,
                        config.getTopicTag(), index, config.getPullBatchSize());
                List<MessageExt> msgs = result.getMsgFoundList();
                if (null != msgs && msgs.size() > 0) {
                    batchMessages = new BatchMessage(msgs, mq);
                    consumer.getConsumer().updateConsumeOffset(mq, result.getMaxOffset());
                    for (MessageExt msg : msgs) {
                        collector.emit(Lists.newArrayList(tx, msg));
                    }
                }
            } catch (MQClientException | RemotingException | MQBrokerException
                    | InterruptedException e) {
                e.printStackTrace();
            }
            return batchMessages;
        }

        @Override
        public void refreshPartitions(List<ISpoutPartition> partitionResponsibilities) {

        }

        @Override
        public void emitPartitionBatch(TransactionAttempt tx, TridentCollector collector,
                                       ISpoutPartition partition, BatchMessage partitionMeta) {

            MessageQueue mq = null;
            try {
                mq = getMessageQueue(config.getTopic()).get(Integer.parseInt(partition.getId()));

                PullResult result = consumer.getConsumer().pullBlockIfNotFound(mq,
                        config.getTopicTag(), partitionMeta.getOffset(),
                        partitionMeta.getMsgList().size());
                List<MessageExt> msgs = result.getMsgFoundList();
                if (null != msgs && msgs.size() > 0) {
                    consumer.getConsumer().updateConsumeOffset(mq, partitionMeta.getNextOffset());
                    for (MessageExt msg : msgs) {
                        collector.emit(Lists.newArrayList(tx, msg));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        @Override
        public void close() {
            LOG.info("close emitter!");
        }

    }

    @Override
    public Coordinator<List<MessageQueue>> getCoordinator(@SuppressWarnings("rawtypes") Map conf,
                                                          TopologyContext context) {
        return new RocketMQCoordinator();
    }

    @Override
    public Emitter<List<MessageQueue>, ISpoutPartition, BatchMessage> getEmitter(@SuppressWarnings("rawtypes") Map conf,
                                                                                 TopologyContext context) {
        return new RocketMQEmitter();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Map getComponentConfiguration() {
        return null;
    }

    @Override
    public Fields getOutputFields() {
        return new Fields("tId", "message");
    }

}
