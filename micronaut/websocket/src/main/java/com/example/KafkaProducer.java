package com.example;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.scheduling.TaskExecutors;
import my.test.protobuf.Envelop;

@KafkaClient(executor = TaskExecutors.MESSAGE_CONSUMER)
public interface KafkaProducer {
    //kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic messaging-topic-snappy-compressed --from-beginning
    @Topic("${kafka.message.topic}")
    void sendProduct(@KafkaKey String key, Envelop.Message name);
}
