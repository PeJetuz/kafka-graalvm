package com.example;

import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import my.test.protobuf.Envelop;

import java.util.function.Predicate;

@ServerWebSocket("/ws/chat/{topic}/{username}")
public class ChatServerWebSocket {

    private static final Logger LOG = LoggerFactory.getLogger(ChatServerWebSocket.class);

    private final WebSocketBroadcaster broadcaster;

    private final KafkaProducer producer;

    private final AtomicInteger counter = new AtomicInteger(0);

    @Inject
    public ChatServerWebSocket(final WebSocketBroadcaster broadcaster, final KafkaProducer producer) {
        this.broadcaster = broadcaster;
        this.producer = producer;
    }

    @OnOpen
    public Publisher<String> onOpen(String topic, String username, WebSocketSession session) {
        log("onOpen", session, username, topic);
        if (topic.equals("all")) {
            return broadcaster.broadcast(String.format("[%s] Now making announcements!", username), isValid(topic));
        }
        return broadcaster.broadcast(String.format("[%s] Joined %s!", username, topic), isValid(topic));
    }

    //curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Host: localhost:8080" -H "Origin: https://localhost:8080" http://localhost:8080/ws/chat/java/Joe
    @OnMessage
    public Publisher<String> onMessage(
        String topic,
        String username,
        String message,
        WebSocketSession session) {

        log("onMessage", session, username, topic);
        Envelop.Message msg = Envelop.Message.newBuilder()
            .setSomeNumber(counter.incrementAndGet())
            .setTopic(topic)
            .setUser(username)
            .addMesssage(message)
            .build();
        producer.sendProduct(username, msg);
        return null;//broadcaster.broadcast(String.format("[%s] %s", username, message), isValid (topic));
    }


    @OnClose
    public Publisher<String> onClose(
        String topic,
        String username,
        WebSocketSession session) {

        log("onClose", session, username, topic);
        return broadcaster.broadcast(
            String.format("[%s] Leaving %s!", username, topic),
            isValid(topic)
        );
    }

    @OnError
    public void error(final Throwable t) {
        if (t instanceof IOException ioe
            && "An established connection was aborted by the software in your host machine".equals(t.getMessage())) {
            //do nothing!
            LOG.info("* WebSocket: try send to closed socket");
        } else {
            LOG.error("Unexpected error: ", t);
        }
    }

    private void log(String event, WebSocketSession session, String username, String topic) {
        LOG.info("* WebSocket: {} received for session {} from '{}' regarding '{}'",
            event, session.getId(), username, topic);
    }

    private Predicate<WebSocketSession> isValid(String topic) {
        return s -> topic.equals("all") //broadcast to all users
            || "all".equals(s.getUriVariables().get("topic", String.class, null)) //"all" subscribes to every topic
            || topic.equalsIgnoreCase(s.getUriVariables().get("topic", String.class, null)); //intra-topic chat
    }
}