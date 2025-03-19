package com.example;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler;
import io.micronaut.context.annotation.Property;
import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import my.test.protobuf.Envelop;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@KafkaListener(
    offsetReset = OffsetReset.EARLIEST,
    offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
    batch = true,
    errorStrategy = @ErrorStrategy(
        value = ErrorStrategyValue.RETRY_ON_ERROR,
        retryDelay = "50ms",
        retryCount = 3,
        exceptionTypes = {IndexOutOfBoundsException.class}
    ),
    properties = {
//        @Property(name = ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, value = "true"),
        @Property(name = ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, value = "false"),
        @Property(name = ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, value = "5000"),
        @Property(name = ConsumerConfig.GROUP_ID_CONFIG, value = "MessageGroup"),
        @Property(
            name = KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
            value = "my.test.protobuf.Envelop$Message"
        ),
    }
)
public class KafkaMsgListener implements KafkaListenerExceptionHandler {
    private final WebSocketBroadcaster broadcaster;

    public KafkaMsgListener(final WebSocketBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Topic("${kafka.message.topic}")
    void receive(List<ConsumerRecord<String, Envelop.Message>> records,
                 Consumer<String, Envelop.Message> kafkaConsumer) {
        System.out.println("Receive: Current thread " + Thread.currentThread().getName() +
            ", time = " + LocalDateTime.now());
        final Map<TopicPartition, OffsetAndMetadata> commits = HashMap.newHashMap(records.size());
        try {
            for (int count = 0; count < records.size(); count++) {
                ConsumerRecord<String, Envelop.Message> record = records.get(count);
                Envelop.Message msg = record.value();

                String topic = record.topic();
                int partition = record.partition();
                long offset = record.offset();
                TopicPartition tp = new TopicPartition(topic, partition);
                OffsetAndMetadata oam = commits.get(tp);
                if (oam == null) {
                    commits.put(
                        tp,
                        new OffsetAndMetadata(offset + 1, "my metadata")
                    );
                } else {
                    commits.put(
                        tp,
                        new OffsetAndMetadata(
                            oam.offset() + 1,
                            oam.metadata()
                        )
                    );
                }
                this.broadcaster.broadcast(
                    "From kafka: %s".formatted(msg),
                    isValid(msg.getTopic())).subscribe(new EmptySubscriber<>());
            }
        } finally {
            kafkaConsumer.commitSync(commits);
        }
    }

    @Override
    public void handle(KafkaListenerException exception) {
        final String msg = exception.getMessage();
        if (exception.getCause() != null && exception.getCause() instanceof RecordDeserializationException) {
            System.out.println("Handle: Error deserializing record, current thread " + Thread.currentThread().getName() +
                ", time = " + LocalDateTime.now());
        } else {
            System.out.println("Handle: Other error, current thread " + Thread.currentThread().getName() +
                ", time = " + LocalDateTime.now());

        }
    }

    private Predicate<WebSocketSession> isValid(String topic) {
        return s -> topic.equals("all") //broadcast to all users
            || "all".equals(s.getUriVariables().get("topic", String.class, null)) //"all" subscribes to every topic
            || topic.equalsIgnoreCase(s.getUriVariables().get("topic", String.class, null)); //intra-topic chat
    }

    private final class EmptySubscriber <T> implements Subscriber<T> {
        @Override
        public void onSubscribe(Subscription s) {

        }

        @Override
        public void onNext(T t) {

        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onComplete() {

        }
    }
}
