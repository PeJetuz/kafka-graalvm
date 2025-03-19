package my.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.runtime.Micronaut;
import io.micronaut.websocket.WebSocketClient;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.regex.Pattern;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

public class SenderApp {
    private final ApplicationContext context;
    private final String srv;
    private final int port;

    public static void main(String[] args) throws Exception {
        new SenderApp(
            SenderApp.env(
                "SERVER_ADDR",
                "ws://localhost",
                "ws://%s"::formatted
            ),
            SenderApp.env("SERVER_PORT", 8080, Integer::parseInt),
            Micronaut.build(args).packages("my.test").start() //run(SenderApp.class, args)
        ).sendWebsockerServer().context.stop().close();
    }

    public SenderApp(String serv, int port, ApplicationContext context) {
        this.srv = serv;
        this.port = port;
        this.context = context;
    }

    private static <T> T env(final String name, final T def, final Function<String, T> map) {
        final String srv = System.getenv(name);
        if (srv == null) {
            return def;
        } else {
            return map.apply(srv);
        }
    }

    private SenderApp sendWebsockerServer() throws Exception {
        TestWebSocketClient adam = createWebSocketClient("adam", "Cats & Recreation");
        await().until(() ->
            Collections.singletonList("[adam] Joined Cats & Recreation!")
                .equals(adam.getMessagesChronologically()));

        TestWebSocketClient anna = createWebSocketClient("anna", "Cats & Recreation");
        await().until(() ->
            Collections.singletonList("[anna] Joined Cats & Recreation!")
                .equals(anna.getMessagesChronologically()));
        await().until(() ->
            Arrays.asList("[adam] Joined Cats & Recreation!", "[anna] Joined Cats & Recreation!")
                .equals(adam.getMessagesChronologically()));

        TestWebSocketClient ben = createWebSocketClient("ben", "Fortran Tips & Tricks");
        await().until(() ->
            Collections.singletonList("[ben] Joined Fortran Tips & Tricks!")
                .equals(ben.getMessagesChronologically()));
        TestWebSocketClient zach = createWebSocketClient("zach", "all");
        await().until(() ->
            Collections.singletonList("[zach] Now making announcements!")
                .equals(zach.getMessagesChronologically()));
        TestWebSocketClient cienna = createWebSocketClient("cienna", "Fortran Tips & Tricks");
        await().until(() ->
            Collections.singletonList("[cienna] Joined Fortran Tips & Tricks!")
                .equals(cienna.getMessagesChronologically()));
        await().until(() ->
            Arrays.asList("[ben] Joined Fortran Tips & Tricks!", "[zach] Now making announcements!", "[cienna] Joined Fortran Tips & Tricks!")
                .equals(ben.getMessagesChronologically()));

        // should broadcast message to all users inside the topic
        final String adamsGreeting = "Hello, everyone. It's another purrrfect day :-)";
        final String expectedGreeting = "[adam] " + adamsGreeting;
        final Pattern expectedKafka = Pattern.compile("From kafka: topic: \"Cats & Recreation\" user: \"adam\" messsage: \"Hello, everyone\\. It\\\\'s another purrrfect day :-\\)\" some_number: \\d+");
        adam.send(adamsGreeting);
        await().until(() ->
            expectedKafka.matcher(
                SenderApp.replace(adam)
            ).matches()
        );

        //subscribed to "Cats & Recreation"
        await().until(() ->
            expectedKafka.matcher(
                SenderApp.replace(anna)
            ).matches()
        );

        //NOT subscribed to "Cats & Recreation"
        assertNotEquals(expectedGreeting, ben.getLatestMessage());
        assertNotEquals(expectedKafka, ben.getLatestMessage());

        //subscribed to the special "all" topic
        await().until(() ->
            expectedKafka.matcher(
                SenderApp.replace(zach)
            ).matches()
        );

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
        return this;
    }

    private static String replace(TestWebSocketClient user) {
        return user.getLatestMessage().replaceAll("[\\s|\\t|\\r\\n]+", " ").trim();
    }

    private TestWebSocketClient createWebSocketClient(String username, String topic) {
        WebSocketClient webSocketClient = this.context.getBean(WebSocketClient.class);
        URI uri = UriBuilder.of(this.srv)
            .port(this.port)
            .path("ws")
            .path("chat")
            .path("{topic}")
            .path("{username}")
            .expand(CollectionUtils.mapOf("topic", topic, "username", username));
        Publisher<TestWebSocketClient> client = webSocketClient.connect(TestWebSocketClient.class,  uri);
        return Flux.from(client).blockFirst();
    }

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
}
