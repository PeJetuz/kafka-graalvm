package com.example;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.messaging.exceptions.MessagingClientException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import my.test.protobuf.TestOuter;
import my.test.protobuf.TestOuterTwo;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.cactoos.Scalar;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.llorllale.cactoos.matchers.Assertion;
import org.llorllale.cactoos.matchers.HasValue;
import org.llorllale.cactoos.shaded.org.cactoos.text.Joined;

@MicronautTest(propertySources = "classpath:test-application.yml")
@Requires(property = "kafka.test.topic")
final class KafkaSchemaRegistryTestIT {

    /**
     * Timeout operation.
     */
    private static final Duration TIME_OUT_MS = Duration.ofMillis(10_000);

    /**
     * Compression type.
     */
    private static final String COMPRESSION = "snappy";

    /**
     * Kafka bootstrap server.
     */
    private final String bs_srv;

    /**
     * Topic name.
     */
    private final String topic;

    private final KafkaStringProducer stringProducer;
    private final KafkaMessageOneProducer messageOneProducer;
    private final KafkaMessageTwoProducer messageTwoProducer;
    private final KLs listener;

    @Inject
    KafkaSchemaRegistryTestIT(final KafkaStringProducer strProducer,
                              final KafkaMessageOneProducer msgOneProducer,
                              final KafkaMessageTwoProducer messageTwoProducer,
                              final KLs list,
                              @Property(name = "kafka.bootstrap.servers") String srv,
                              @Property(name = "kafka.test.topic") String topc
            ) {
        this.stringProducer = strProducer;
        this.messageOneProducer = msgOneProducer;
        this.messageTwoProducer = messageTwoProducer;
        this.listener = list;
        this.topic = topc;
        this.bs_srv = srv;
    }

    @BeforeEach
    void init() throws ExecutionException, InterruptedException {
        this.createTopic();
    }

    @AfterEach
    void cleanup() throws ExecutionException, InterruptedException {
        this.removeTopic();
    }

    @Test
    void test() throws Exception {
        TestOuter.Message one = KafkaSchemaRegistryTestIT.message(1);;
        this.messageOneProducer.sendProduct("first", one);
        MessagingClientException exception = Assertions.assertThrows(
            MessagingClientException.class,
            () -> this.messageTwoProducer.sendProduct(
                "Let's try sending an error message Message2",
                TestOuterTwo.Message2.newBuilder()
                    .setTitle("test_two_%s\n".formatted(2))
                    .setSomeNumber(2)
                    .addMesssage("test two msg one\n")
                    .addMesssage("test two msg two\n")
                    .build()
            ),
            "Expected MessagingClientException to throw, but it didn't"
        );
        new Assertion<Scalar<String>>(
            "Expected message must be equals",
            () -> exception.getMessage(),
            new HasValue<>(
                new Joined(
                    "\n",
                    "Exception sending producer record for method [void sendProduct(String key,Message2 value)]: Error registering Protobuf schema: syntax = \"proto3\";",
                    "",
                    "option java_package = \"my.test.protobuf\";",
                    "option java_outer_classname = \"TestOuterTwo\";",
                    "",
                    "message Message2 {",
                    "  string title = 1;",
                    "  repeated string messsage = 2;",
                    "  int32 some_number = 3;",
                    "}",
                    ""
                ).asString()
            )
        ).affirm();
        this.stringProducer.sendProduct("str1", "str1");
        TestOuter.Message two = KafkaSchemaRegistryTestIT.message(2);;
        this.messageOneProducer.sendProduct("second", two);
        TestOuter.Message three = KafkaSchemaRegistryTestIT.message(3);
        this.messageOneProducer.sendProduct("third", three);

        this.listener.waitLock();
        new Assertion<>(
            "Expected five messages received",
            this.listener.recvd().size(),
            new IsEqual<>(5)
        ).affirm();
        assertMessage(one, 0);
        assertMessage(two, 1);
        assertMessage(two, 2);
        assertMessage(two, 3);
        assertMessage(three, 4);
    }

    private void assertMessage(final TestOuter.Message one, final int idx) {
        new Assertion<>(
            "The message must be the same as the one sent.",
            one,
            new IsEqual<>(this.listener.recvd().get(idx))
            ).affirm();
    }

    private void createTopic() throws ExecutionException, InterruptedException {
        final Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.bs_srv);
        props.put(AdminClientConfig.RETRIES_CONFIG, 3);
        try (Admin admin = Admin.create(props)) {
            final NewTopic ntopic = new NewTopic(this.topic, 1, (short) 1);
            final CreateTopicsResult result = admin.createTopics(
                Collections.singleton(ntopic)
            );
            result.all().get();
        }
    }

    private void removeTopic() throws ExecutionException, InterruptedException {
        final Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.bs_srv);
        props.put(AdminClientConfig.RETRIES_CONFIG, 3);
        try (Admin admin = Admin.create(props)) {
            final DeleteTopicsResult result = admin.deleteTopics(
                Collections.singleton(this.topic)
            );
            result.all().get();
        }
    }

    private static TestOuter.Message message(final int idx) {
        return TestOuter.Message.newBuilder()
            .setTitle("test_%s\n".formatted(idx))
            .setSomeNumber(idx)
            .addMesssage("test one %d msg one\n".formatted(idx))
            .addMesssage("test one %d msg two\n".formatted(idx))
            .build();
    }

    @KafkaListener(offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        batch = true,
        errorStrategy = @ErrorStrategy(
            value = ErrorStrategyValue.RETRY_ON_ERROR,
            retryDelay = "50ms",
            retryCount = 3,
            exceptionTypes = {IndexOutOfBoundsException.class}
        ),
        properties = {
            @Property(name = ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, value = "false"),
            @Property(name = ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, value = "5000"),
            @Property(name = ConsumerConfig.GROUP_ID_CONFIG, value = "MessageOne"),
            @Property(
                name = KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
                value = "my.test.protobuf.TestOuter$Message"
            ),
//        @Property(name = ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, value = "earliest"),
        }
    )
    public static class KLs implements KafkaListenerExceptionHandler {
        private final List<TestOuter.Message> received = new CopyOnWriteArrayList<>();
        private final AtomicBoolean competed = new AtomicBoolean(false);
        private final CountDownLatch lock = new CountDownLatch(2);

        @Topic("${kafka.test.topic}")
        void receive(List<ConsumerRecord<String, TestOuter.Message>> records,
                     Consumer<String, TestOuter.Message> kafkaConsumer) {
            System.out.println("Receive: Current thread " + Thread.currentThread().getName() +
                ", time = " + LocalDateTime.now());
            final Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>(records.size());
            try {
                for (int count = 0; count < records.size(); count++) {
                    ConsumerRecord<String, TestOuter.Message> record = records.get(count);
                    TestOuter.Message msg = record.value();
                    received.add(msg);
                    if (received.size() == 2 || received.size() == 3) {
                        throw new IndexOutOfBoundsException(2);
                    }

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

                }
                this.lock.countDown();
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

        List<TestOuter.Message> recvd() {
            return received;
        }

        void waitLock() {
            System.out.println("Current thread await " + Thread.currentThread().getName() +
                ", time = " + LocalDateTime.now());
            try {
                System.out.printf(
                    "Current thread %s return %b after await, time = %s %n",
                    Thread.currentThread().getName(),
                    this.lock.await(10, TimeUnit.SECONDS),
                    LocalDateTime.now().toString()
                );
            } catch (InterruptedException e) {
                System.err.println("Error: try locking failed!");
            }
        }
    }

    @KafkaClient(
        executor = TaskExecutors.BLOCKING,
        properties = {
            @Property(name = ProducerConfig.ACKS_CONFIG, value = "all"),
            @Property(name = ProducerConfig.RETRIES_CONFIG, value = "3"),
            @Property(name = ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, value = "1000"),
            @Property(name = ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, value = "500"),
            @Property(name = ProducerConfig.LINGER_MS_CONFIG, value = "1"),
            @Property(
                name = ProducerConfig.COMPRESSION_TYPE_CONFIG,
                value = KafkaSchemaRegistryTestIT.COMPRESSION
            ),
//            @Property(
//                name = KafkaProtobufDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
//                value = KafkaSchemaRegistryTest.SCHEMA_REGISTRY_URL
//            ),
        })
    public interface KafkaMessageOneProducer {
        //kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic messaging-topic-snappy-compressed --from-beginning
        @Topic("${kafka.test.topic}")
        void sendProduct(@KafkaKey String key, TestOuter.Message value);
    }

    @KafkaClient(
        executor = TaskExecutors.BLOCKING,
        properties = {
            @Property(name = ProducerConfig.ACKS_CONFIG, value = "all"),
            @Property(name = ProducerConfig.RETRIES_CONFIG, value = "3"),
            @Property(name = ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, value = "1000"),
            @Property(name = ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, value = "500"),
            @Property(name = ProducerConfig.LINGER_MS_CONFIG, value = "1"),
            @Property(
                name = ProducerConfig.COMPRESSION_TYPE_CONFIG,
                value = KafkaSchemaRegistryTestIT.COMPRESSION
            ),
        })
    public interface KafkaMessageTwoProducer {
        //kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic messaging-topic-snappy-compressed --from-beginning
        @Topic("${kafka.test.topic}")
        void sendProduct(@KafkaKey String key, TestOuterTwo.Message2 value);
    }

    @KafkaClient(
        executor = TaskExecutors.BLOCKING,
        acks = KafkaClient.Acknowledge.ALL,
        properties = {
            @Property(name = ProducerConfig.RETRIES_CONFIG, value = "3"),
            @Property(name = ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, value = "1000"),
            @Property(name = ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, value = "500"),
            @Property(name = ProducerConfig.LINGER_MS_CONFIG, value = "1"),
            @Property(
                name = ProducerConfig.COMPRESSION_TYPE_CONFIG,
                value = KafkaSchemaRegistryTestIT.COMPRESSION
            ),
            @Property(
                name = ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                value = "org.apache.kafka.common.serialization.StringSerializer"
            )
        })
    public interface KafkaStringProducer {
        //kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic messaging-test-topic-snappy-compressed_d9a7c412aa --from-beginning
        @Topic("${kafka.test.topic}")
        void sendProduct(@KafkaKey String key, String value);
    }
}
