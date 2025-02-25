package io.helidon.examples.quickstart.se;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.Config;
import io.helidon.http.Status;
import io.helidon.messaging.Channel;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.Messaging;
import io.helidon.messaging.connectors.kafka.KafkaConnector;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import jakarta.json.Json;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import my.test.protobuf.Envelop;
import org.apache.kafka.common.serialization.StringSerializer;


/**
 * A simple service to greet you. Examples:
 * <p>
 * Get default greeting message:
 * {@code curl -X GET http://localhost:8080/greet}
 * <p>
 * Get greeting message for Joe:
 * {@code curl -X GET http://localhost:8080/greet/Joe}
 * <p>
 * Change greeting
 * {@code curl -X PUT -H "Content-Type: application/json" -d '{"greeting" : "Howdy"}' http://localhost:8080/greet/greeting}
 * <p>
 * The message is returned as a JSON object
 */
class GreetService implements HttpService {


    private static final JsonBuilderFactory JSON = Json.createBuilderFactory(Collections.emptyMap());

    private final Emitter<Envelop.Message> emitter;
    private final Messaging messaging;
    private final AtomicInteger counter;

    /**
     * The config value for the key {@code greeting}.
     */
    private final AtomicReference<String> greeting = new AtomicReference<>();

    GreetService() {
        this(Config.global().get("app"));
    }

    //messaging-greet-topic-snappy-compressed
    //kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic messaging-greet-topic-snappy-compressed --from-beginning
    GreetService(Config config) {
        this.counter = new AtomicInteger(0);
        greeting.set(config.get("greeting").asString().orElse("Ciao"));
        String kafkaServer = config.get("kafka.bootstrap.servers").asString().get();
        String topic = config.get("kafka.topic").asString().get();
        String compression = config.get("kafka.compression").asString().orElse("none");
        String schemaRegistryUrl = config.get("kafka.schema.registry.url").asString().get();

        // Prepare channel for connecting processor -> kafka connector with specific subscriber configuration,
        // channel -> connector mapping is automatic when using KafkaConnector.configBuilder()
        Channel<Envelop.Message> toKafka = Channel.<Envelop.Message>builder()
            .subscriberConfig(KafkaConnector.configBuilder()
                .property("schema.registry.url", schemaRegistryUrl)
                .bootstrapServers(kafkaServer)
                .topic(topic)
                .compressionType(compression)
                .keySerializer(StringSerializer.class)
                .valueSerializer(KafkaProtobufSerializerTestMsg.class)
                .build())
            .build();

        // Prepare channel for connecting emitter -> processor
        Channel<Envelop.Message> toProcessor = Channel.create();

        // Prepare Kafka connector, can be used by any channel
        KafkaConnector kafkaConnector = KafkaConnector.create();

        // Prepare emitter for manual publishing to channel
        emitter = Emitter.create(toProcessor);

        // Transforming to upper-case before sending to kafka
        messaging = Messaging.builder()
            .emitter(emitter)
            // Processor connect two channels together
            .processor(toProcessor, toKafka,
                m -> Envelop.Message.newBuilder(m).setTitle(m.getTitle().toUpperCase()).build())
//                String::toUpperCase)
            .connector(kafkaConnector)
            .build()
            .start();
    }

    /**
     * A service registers itself by updating the routing rules.
     *
     * @param rules the routing rules.
     */
    @Override
    public void routing(HttpRules rules) {
        rules
                .get("/", this::getDefaultMessageHandler)
                .get("/{name}", this::getMessageHandler)
                .put("/greeting", this::updateGreetingHandler);
    }

    /**
     * Return a worldly greeting message.
     *
     * @param request  the server request
     * @param response the server response
     */
    private void getDefaultMessageHandler(ServerRequest request,
                                          ServerResponse response) {
        sendResponse(response, "World");
    }

    /**
     * Return a greeting message using the name that was provided.
     *
     * @param request  the server request
     * @param response the server response
     */
    private void getMessageHandler(ServerRequest request,
                                   ServerResponse response) {
        String name = request.path().pathParameters().get("name");
        Envelop.Message tmsg = Envelop.Message.newBuilder()
            .setTitle(name)
            .setSomeNumber(counter.incrementAndGet())
            .addMesssage(name + "_0")
            .addMesssage(name + "_1")
            .build();
        System.out.println("Emitting: " + name);
        emitter.send(tmsg);
        sendResponse(response, name);
    }

    private void sendResponse(ServerResponse response, String name) {
        String msg = String.format("%s %s!", greeting.get(), name);

        JsonObject returnObject = JSON.createObjectBuilder()
                .add("message", msg)
                .build();
        response.send(returnObject);
    }

    private void updateGreetingFromJson(JsonObject jo, ServerResponse response) {

        if (!jo.containsKey("greeting")) {
            JsonObject jsonErrorObject = JSON.createObjectBuilder()
                    .add("error", "No greeting provided")
                    .build();
            response.status(Status.BAD_REQUEST_400)
                    .send(jsonErrorObject);
            return;
        }

        greeting.set(jo.getString("greeting"));
        response.status(Status.NO_CONTENT_204).send();
    }

    /**
     * Set the greeting to use in future messages.
     *
     * @param request  the server request
     * @param response the server response
     */
    private void updateGreetingHandler(ServerRequest request,
                                       ServerResponse response) {
        updateGreetingFromJson(request.content().as(JsonObject.class), response);
    }

    public static final class KafkaProtobufSerializerTestMsg extends KafkaProtobufSerializer<Envelop.Message> {
    }

}
