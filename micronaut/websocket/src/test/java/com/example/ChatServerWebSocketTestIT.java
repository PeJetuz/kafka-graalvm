package com.example;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.WebSocketClient;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;
import jakarta.inject.Inject;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
@Property(name = "spec.name", value = "ChatWebSocketTest")
@MicronautTest(propertySources = "classpath:test-application.yml")
class ChatServerWebSocketTestIT {

    @Inject
    BeanContext beanContext;

    private final EmbeddedServer embeddedServer;
    private final String bs_srv;
    private final String topic;

    @Requires(property = "spec.name", value = "ChatWebSocketTest")
    @ClientWebSocket
    static abstract class TestWebSocketClient implements AutoCloseable {

        private final Deque<String> messageHistory = new ConcurrentLinkedDeque<>();

        public String getLatestMessage() {
            return messageHistory.peekLast();
        }

        public List<String> getMessagesChronologically() {
            return new ArrayList<>(messageHistory);
        }

        @OnMessage
        void onMessage(String message) {
            messageHistory.add(message);
        }

        abstract void send(@NonNull @NotBlank String message);
    }

    @Inject
    public ChatServerWebSocketTestIT(
        final EmbeddedServer embeddedServer,
        @Property(name = "kafka.bootstrap.servers") final String srv,
        @Property(name = "kafka.message.topic") final String topc
    ) {
        this.embeddedServer = embeddedServer;
        this.bs_srv = srv;
        this.topic = topc;
    }

    @BeforeEach
    void init() throws ExecutionException, InterruptedException {
        this.createTopic();
    }

    @AfterEach
    void cleanup() throws ExecutionException, InterruptedException {
        this.removeTopic();
    }

    private TestWebSocketClient createWebSocketClient(int port, String username, String topic) {
        WebSocketClient webSocketClient = beanContext.getBean(WebSocketClient.class);
        URI uri = UriBuilder.of("ws://localhost")
            .port(port)
            .path("ws")
            .path("chat")
            .path("{topic}")
            .path("{username}")
            .expand(CollectionUtils.mapOf("topic", topic, "username", username));
        Publisher<TestWebSocketClient> client = webSocketClient.connect(TestWebSocketClient.class,  uri);
        return Flux.from(client).blockFirst();
    }

    @Test
    void testWebsockerServer() throws Exception {
        TestWebSocketClient adam = createWebSocketClient(embeddedServer.getPort(), "adam", "Cats & Recreation");
        await().until(() ->
            Collections.singletonList("[adam] Joined Cats & Recreation!")
                .equals(adam.getMessagesChronologically()));

        TestWebSocketClient anna = createWebSocketClient(embeddedServer.getPort(), "anna", "Cats & Recreation");
        await().until(() ->
            Collections.singletonList("[anna] Joined Cats & Recreation!")
                .equals(anna.getMessagesChronologically()));
        await().until(() ->
            Arrays.asList("[adam] Joined Cats & Recreation!", "[anna] Joined Cats & Recreation!")
                .equals(adam.getMessagesChronologically()));

        TestWebSocketClient ben = createWebSocketClient(embeddedServer.getPort(), "ben", "Fortran Tips & Tricks");
        await().until(() ->
            Collections.singletonList("[ben] Joined Fortran Tips & Tricks!")
                .equals(ben.getMessagesChronologically()));
        TestWebSocketClient zach = createWebSocketClient(embeddedServer.getPort(), "zach", "all");
        await().until(() ->
            Collections.singletonList("[zach] Now making announcements!")
                .equals(zach.getMessagesChronologically()));
        TestWebSocketClient cienna = createWebSocketClient(embeddedServer.getPort(), "cienna", "Fortran Tips & Tricks");
        await().until(() ->
            Collections.singletonList("[cienna] Joined Fortran Tips & Tricks!")
                .equals(cienna.getMessagesChronologically()));
        await().until(() ->
            Arrays.asList("[ben] Joined Fortran Tips & Tricks!", "[zach] Now making announcements!", "[cienna] Joined Fortran Tips & Tricks!")
                .equals(ben.getMessagesChronologically()));

        // should broadcast message to all users inside the topic
        final String adamsGreeting = "Hello, everyone. It's another purrrfect day :-)";
        final String expectedGreeting = "[adam] " + adamsGreeting;
        final String expectedKafka = "From kafka: topic: \"Cats & Recreation\"\n" +
            "user: \"adam\"\n" +
            "messsage: \"Hello, everyone. It\\'s another purrrfect day :-)\"\n" +
            "some_number: 1\n";
        adam.send(adamsGreeting);

        //subscribed to "Cats & Recreation"
//        await().until(() ->
//            expectedGreeting.equals(adam.getLatestMessage()));
        await().until(() ->
            expectedKafka.equals(adam.getLatestMessage()));

        //subscribed to "Cats & Recreation"
        await().until(() ->
            expectedKafka.equals(anna.getLatestMessage()));

        //NOT subscribed to "Cats & Recreation"
        assertNotEquals(expectedGreeting, ben.getLatestMessage());
        assertNotEquals(expectedKafka, ben.getLatestMessage());

        //subscribed to the special "all" topic
        await().until(() ->
            expectedKafka.equals(zach.getLatestMessage()));

        //NOT subscribed to "Cats & Recreation"
        assertNotEquals(expectedGreeting, cienna.getLatestMessage());
        assertNotEquals(expectedKafka, cienna.getLatestMessage());

        // should broadcast message when user disconnects from the chat

        anna.close();

        String annaLeaving = "[anna] Leaving Cats & Recreation!";
        await().until(() ->
            annaLeaving.equals(adam.getLatestMessage()));

        //subscribed to "Cats & Recreation"
        assertEquals(annaLeaving, adam.getLatestMessage());

        //Anna already left and therefore won't see the message about her leaving
        assertNotEquals(annaLeaving, anna.getLatestMessage());

        //NOT subscribed to "Cats & Recreation"
        assertNotEquals(annaLeaving, ben.getLatestMessage());

        //subscribed to the special "all" topic
        assertEquals(annaLeaving, zach.getLatestMessage());

        //NOT subscribed to "Cats & Recreation"
        assertNotEquals(annaLeaving, cienna.getLatestMessage());

        adam.close();
        ben.close();
        zach.close();
        cienna.close();
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
}