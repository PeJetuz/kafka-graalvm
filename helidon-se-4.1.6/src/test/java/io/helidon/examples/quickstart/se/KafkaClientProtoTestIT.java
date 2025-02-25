package io.helidon.examples.quickstart.se;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import my.test.protobuf.TestOuter;
import my.test.protobuf.TestOuterTwo;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class KafkaClientProtoTestIT {

    /**
     * Kafka bootstrap server.
     */
    private static final String BS_SRV = "localhost:9094";

    /**
     * Timeout operation.
     */
    private static final Duration TIME_OUT_MS = Duration.ofMillis(10_000);

    /**
     * Compression type.
     */
    private static final String COMPRESSION = "snappy";

    /**
     * Service registry url.
     */
    private static final String SCHEMA_REGISTRY_URL = "http://127.0.0.1:8081";

    /**
     * Topic name.
     */
    private final String topic;

    //kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic messaging-test-topic-snappy-compressed --from-beginning
    KafkaClientProtoTestIT() {
        this.topic =
            "messaging-test-topic-snappy-compressed_%d"
                .formatted(new Random().nextLong());
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
    void sendProtobufAndString() throws Exception {
        sendAndVerifyMessage(0, 5);

        Assertions.assertThrows(
            org.apache.kafka.common.errors.SerializationException.class,
            () -> sendBadMessage(1, 2),
            "Impossible to send another schema"
        );

        sendAndVerifyString();

        sendAndVerifyMessage(40, 5);

        skipBadMessage();
    }

    private void skipBadMessage() {
        final AtomicLong startpos = new AtomicLong(-1);
        final AtomicLong endpos = new AtomicLong(-1);
        try (Consumer<String, TestOuter.Message> consumer =
                 this.createConsumer(
                     "g1",
                     TestOuter.Message.class.getName(),
                     KafkaProtoTestIT.KafkaProtobufDeserializerTestMsg.class)
        ) {
            for (int i = 0; i < 10; i++) {
                Assertions.assertThrows(
                    RecordDeserializationException.class,
                    () -> consumer.poll(KafkaClientProtoTestIT.TIME_OUT_MS),
                    "Expected RecordDeserializationException to throw, but it didn't"
                );
                Set<TopicPartition> tps = consumer.assignment();
                MatcherAssert.assertThat(
                    "Partitions count must be equals",
                    tps.size(),
                    Matchers.equalTo(1)
                );
                MatcherAssert.assertThat(
                    "Partition topic must be equals",
                    tps.iterator().next().topic(),
                    Matchers.equalTo(this.topic)
                );
                tps.forEach(tp -> {
                    long pos = consumer.position(
                        tp,
                        KafkaClientProtoTestIT.TIME_OUT_MS
                    );
                    consumer.seek(tp, pos + 1);
                    consumer.commitSync();
                    if (startpos.longValue() == -1) {
                        startpos.set(pos);
                    }
                    endpos.set(pos);
                });
            }
            readStringMessages(startpos.longValue(), endpos.longValue());

            final ConsumerRecords<String, TestOuter.Message> lastread =
                consumer.poll(KafkaClientProtoTestIT.TIME_OUT_MS);
            consumer.commitSync();
            KafkaClientProtoTestIT.assertRead(lastread, 40, 10);
        }
    }

    private void sendAndVerifyMessage(final int from, final int size)
        throws ExecutionException, InterruptedException
    {
        try (Consumer<String, TestOuter.Message> consumer =
                 this.createConsumer(
                     "g1",
                     TestOuter.Message.class.getName(),
                     KafkaProtoTestIT.KafkaProtobufDeserializerTestMsg.class);
             Producer<String, TestOuter.Message> producer =
                 new KafkaProducer<>(
                     KafkaClientProtoTestIT.produserProps(
                         KafkaProtobufSerializerTestMsg.class
                     )
                 )
        ) {
            final int count = from + size;
            this.send(producer, from, count);
            final ConsumerRecords<String, TestOuter.Message> fread =
                consumer.poll(KafkaClientProtoTestIT.TIME_OUT_MS);
            consumer.seekToBeginning(consumer.assignment());
            this.send(producer, count, count + size);
            final ConsumerRecords<String, TestOuter.Message> allread =
                consumer.poll(KafkaClientProtoTestIT.TIME_OUT_MS);
            consumer.commitSync();
            KafkaClientProtoTestIT.assertRead(fread, from, size);
            KafkaClientProtoTestIT.assertRead(allread, 0, size * 2);
        }
    }

    private void sendAndVerifyString()
        throws ExecutionException, InterruptedException
    {
        try (Consumer<String, String> consumer =
             this.createConsumer(
                 "g1",
                 String.class.getName(),
                 StringDeserializer.class
             );
             Producer<String, String> producer =
                 new KafkaProducer<>(
                     KafkaClientProtoTestIT.produserProps(
                         StringSerializer.class
                     )
                 )
        ) {
            this.sendString(producer, 0, 5);
            final ConsumerRecords<String, String> fread =
                consumer.poll(KafkaClientProtoTestIT.TIME_OUT_MS);
            this.sendString(producer, 5, 10);
            consumer.seekToBeginning(consumer.assignment());
            final ConsumerRecords<String, String> allread =
                consumer.poll(KafkaClientProtoTestIT.TIME_OUT_MS);
            consumer.commitSync();
            KafkaClientProtoTestIT.assertReadString(fread, 0, 5, 0);
            KafkaClientProtoTestIT.assertReadString(allread, 0, 20, 10);
        }
    }

    private void readStringMessages(final long start, final long end) {
        try (Consumer<String, String> cons =
             this.createConsumer(
                 "g2",
                  String.class.getName(),
                  StringDeserializer.class
             )
        ) {
            cons.poll(KafkaClientProtoTestIT.TIME_OUT_MS);
            Set<TopicPartition> tps = cons.assignment();
            MatcherAssert.assertThat(
                "Partitions count must be equals",
                tps.size(),
                Matchers.equalTo(1)
            );
            MatcherAssert.assertThat(
                "Partition topic must be equals",
                tps.iterator().next().topic(),
                Matchers.equalTo(this.topic)
            );
            tps.forEach(stp -> {
                cons.seek(stp, start);
                final ConsumerRecords<String, String> strmsg = cons.poll(
                    KafkaClientProtoTestIT.TIME_OUT_MS
                );
                cons.commitSync();
                KafkaClientProtoTestIT.assertReadString(
                    strmsg,
                    (int)((end + 1) - start)
                );
            });
        }
    }

    private static void assertRead(
        final ConsumerRecords<String, TestOuter.Message> records,
        final int from,
        final int size
    ) {
        MatcherAssert.assertThat(
            "Record count must be equals",
            records.count(),
            Matchers.equalTo(size));
        final AtomicInteger count = new AtomicInteger(from);
        records.forEach(
            r -> {
                final int cnt = count.intValue();
                final String key = count.toString();
                final TestOuter.Message value = TestOuter.Message
                    .newBuilder()
                    .setTitle("test_%s\n".formatted(key))
                    .setSomeNumber(count.intValue())
                    .addMesssage("test one msg one\n")
                    .addMesssage("test one msg two\n")
                    .build();
                MatcherAssert.assertThat(
                    "Key should be equals",
                    r.key(),
                    Matchers.equalTo(key)
                );
                MatcherAssert.assertThat(
                    "Value should be equals",
                    r.value(),
                    Matchers.equalTo(value)
                );
                count.incrementAndGet();
            });
    }

    private static void assertReadString(
        final ConsumerRecords<String, String> records,
        final int size
    ) {
        MatcherAssert.assertThat(
            "Size must be more than 10",
            size,
            Matchers.greaterThanOrEqualTo(10)
        );
        MatcherAssert.assertThat(
            "Records must be more than size",
            records.count(),
            Matchers.greaterThanOrEqualTo(size)
        );
        final AtomicInteger count = new AtomicInteger(0);
        records.forEach(
            r -> {
                if (count.intValue() < size) {
                    final String key = count.toString();
                    final String value =
                        "event %d".formatted(count.intValue());
                    MatcherAssert.assertThat(
                        "Key should be equals",
                        r.key(),
                        Matchers.equalTo(key)
                    );
                    MatcherAssert.assertThat(
                        "Value should be equals",
                        r.value(),
                        Matchers.equalTo(value)
                    );
                }
                count.incrementAndGet();
            });
    }

    private static void assertReadString(
        final ConsumerRecords<String, String> records,
        final int from,
        final int size,
        final int skip
    ) {
        MatcherAssert.assertThat(
            "Record count must  be equals",
            records.count(),
            Matchers.equalTo(size));
        final AtomicInteger count = new AtomicInteger(from);
        records.forEach(
            r -> {
                if (count.intValue() >= skip) {
                    final int cnt = count.intValue() - skip;
                    final String key = "%d".formatted(cnt);
                    final String value = "event %d".formatted(cnt);
                    MatcherAssert.assertThat(
                        "Key should be equals",
                        r.key(),
                        Matchers.equalTo(key)
                    );
                    MatcherAssert.assertThat(
                        "Value should be equals",
                        r.value(),
                        Matchers.equalTo(value)
                    );
                }
                count.incrementAndGet();
            });
    }

    private void sendString(
        final Producer<String, String> producer,
        final int from,
        final int count
    ) throws ExecutionException, InterruptedException {
        for (int cnt = from; cnt < count; ++cnt) {
            final String key = String.valueOf(cnt);
            final String value = "event %d".formatted(cnt);
            final ProducerRecord<String, String> prec =
                new ProducerRecord<>(this.topic, key, value);
            final Future<RecordMetadata> result = producer.send(prec);
            result.get();
        }
        producer.flush();
    }

    private void send(
        final Producer<String, TestOuter.Message> producer,
        final int from,
        final int count
    ) throws ExecutionException, InterruptedException
    {
        for (int cnt = from; cnt < count; ++cnt) {
            final String key = String.valueOf(cnt);
            final TestOuter.Message value = TestOuter.Message.newBuilder()
                .setTitle("test_%s\n".formatted(cnt))
                .setSomeNumber(cnt)
                .addMesssage("test one msg one\n")
                .addMesssage("test one msg two\n")
                .build();
            final ProducerRecord<String, TestOuter.Message> prec =
                new ProducerRecord<>(this.topic, key, value);
            final Future<RecordMetadata> result = producer.send(prec);
            result.get();
        }
        producer.flush();
    }

    private void sendBadMessage(final int from, final int count)
        throws ExecutionException, InterruptedException {
        try (Producer<String, TestOuterTwo.Message2> producer =
            new KafkaProducer<>(KafkaClientProtoTestIT.produserProps(
                KafkaProtobufSerializerTestMsgTwo.class
            ))
        ) {
            for (int cnt = from; cnt < count; ++cnt) {
                final String key = String.valueOf(cnt);
                final TestOuterTwo.Message2 value = TestOuterTwo.Message2
                    .newBuilder()
                    .setTitle("test_%s\n".formatted(cnt))
                    .setSomeNumber(cnt)
                    .addMesssage("test one msg one\n")
                    .addMesssage("test one msg two\n")
                    .build();
                final ProducerRecord<String, TestOuterTwo.Message2> prec =
                    new ProducerRecord<>(this.topic, key, value);
                final Future<RecordMetadata> result = producer.send(prec);
                result.get();
            }
            producer.flush();
        }
    }

    private <T> Consumer<String, T> createConsumer(
        final String group,
        final String desClassName,
        final Class<? extends Deserializer<T>> deserializer
    ) {
        final Consumer<String, T> consumer =
            new KafkaConsumer<>(KafkaClientProtoTestIT
                .consumerProps(group, desClassName, deserializer)
            );
        consumer.subscribe(Collections.singletonList(this.topic));
        return consumer;
    }

    private static <T> Properties consumerProps(
        final String group,
        final String desClassName,
        final Class<? extends Deserializer<T>> deserializer
    ) {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaClientProtoTestIT.BS_SRV
        );
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName()
        );
        props.put(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            deserializer.getName()
        );
        props.put(KafkaProtobufDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
            KafkaClientProtoTestIT.SCHEMA_REGISTRY_URL);
        //                   .property("derive.type", "true")
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
            desClassName
        );
        return props;
    }

    private static Properties produserProps(
        final Class<? extends Serializer<?>> serializer
    ) {
        final Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaClientProtoTestIT.BS_SRV
        );
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName()
        );
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            serializer.getName()
        );
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,
            KafkaClientProtoTestIT.COMPRESSION
        );
        props.put(KafkaProtobufDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
            KafkaClientProtoTestIT.SCHEMA_REGISTRY_URL
        );
        return props;
    }

    private void createTopic() throws ExecutionException, InterruptedException {
        final Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaClientProtoTestIT.BS_SRV
        );
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
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaClientProtoTestIT.BS_SRV
        );
        props.put(AdminClientConfig.RETRIES_CONFIG, 3);
        try (Admin admin = Admin.create(props)) {
            final DeleteTopicsResult result = admin.deleteTopics(
                Collections.singleton(this.topic)
            );
            result.all().get();
        }
    }

    public static final class KafkaProtobufSerializerTestMsg extends KafkaProtobufSerializer<TestOuter.Message> {
    }
    public static final class KafkaProtobufDeserializerTestMsg extends KafkaProtobufDeserializer<TestOuter.Message> {
    }
    public static final class KafkaProtobufSerializerTestMsgTwo extends KafkaProtobufSerializer<TestOuterTwo.Message2> {
    }

}
