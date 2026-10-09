package com.nickneim.jeopardy.session.security;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open STOMP connections of each player, so the server can close them
 * when the player's token stops working (they left or were kicked). A closed
 * client that reconnects with the same token is then rejected on CONNECT, the
 * same as any other unknown token.
 *
 * <p>A connection is closed by sending a STOMP ERROR frame, after which Spring
 * closes the WebSocket. That close discards any frame still waiting to be
 * written: Spring's ConcurrentWebSocketSessionDecorator only queues a frame
 * when another thread is writing to the same connection, and stops writing
 * the queue once a close starts. So an ERROR frame sent while a broadcast
 * was being written to the same connection (the departure broadcast always
 * is) was usually lost, and the client only saw the socket close.
 *
 * <p>To deliver it reliably, this is also an interceptor on the client
 * outbound channel (see WebSocketConfig) that tracks the frames of each
 * player connection that have been sent but not yet written. Closing a
 * connection stops any new frames to it, lets the ones in flight finish (so
 * the departure broadcast still arrives), and only then sends the ERROR
 * frame, which is then the only thing being written to that connection.
 */
@Component
public class PlayerConnections implements ExecutorChannelInterceptor {

    // STOMP session id (one per connection) -> the connection.
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    private final MessageChannel clientOutboundChannel;

    // The outbound channel is lazy because the broker configuration that
    // creates it also registers this as its interceptor.
    public PlayerConnections(
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.clientOutboundChannel = clientOutboundChannel;
    }

    public void connected(String connectionId, PlayerPrincipal player) {
        connections.put(connectionId, new Connection(player));
    }

    public void disconnected(String connectionId) {
        connections.remove(connectionId);
    }

    /**
     * Closes every connection of this player, with {@code message} in the
     * ERROR frame. Frames already sent to them are delivered first; anything
     * sent afterwards is dropped.
     */
    public void close(PlayerPrincipal player, String message) {
        List<String> readyToClose = new ArrayList<>();

        connections.forEach((connectionId, connection) -> {
            if (!connection.player.equals(player)) {
                return;
            }

            synchronized (connection) {
                if (connection.closing) {
                    return;
                }

                connection.closing = true;
                connection.closeMessage = message;

                if (connection.inFlight.isEmpty()) {
                    connection.closeMessage = null;
                    readyToClose.add(connectionId);
                }
            }
        });

        readyToClose.forEach(connectionId -> sendError(connectionId, message));
    }

    /**
     * Sends an ERROR frame to one connection, which closes it. Never put a
     * token in the message: it could end up in logs. Used directly only where
     * nothing else can be writing to the connection yet (a rejected CONNECT).
     */
    public void sendError(String connectionId, String message) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(message);
        error.setSessionId(connectionId);

        clientOutboundChannel.send(
                MessageBuilder.createMessage(new byte[0], error.getMessageHeaders())
        );
    }

    // -------------------------------------------------------------------------
    // Outbound channel interceptor
    // -------------------------------------------------------------------------

    // Runs on the sending thread, before the frame is queued for writing.
    @Override
    public @Nullable Message<?> preSend(Message<?> message, MessageChannel channel) {
        Connection connection = connectionOf(message);

        if (connection == null || isError(message)) {
            return message;
        }

        synchronized (connection) {
            if (connection.closing) {
                return null;
            }

            connection.inFlight.add(message);
        }

        return message;
    }

    // Lets afterMessageHandled run for this message.
    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return message;
    }

    // Runs on the writing thread, once the frame has been written (or failed).
    @Override
    public void afterMessageHandled(
            Message<?> message,
            MessageChannel channel,
            MessageHandler handler,
            @Nullable Exception ex
    ) {
        finished(message);
    }

    // A frame that was never queued for writing (the executor rejected it).
    @Override
    public void afterSendCompletion(
            Message<?> message,
            MessageChannel channel,
            boolean sent,
            @Nullable Exception ex
    ) {
        if (ex != null) {
            finished(message);
        }
    }

    private void finished(Message<?> message) {
        String connectionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        Connection connection = connectionOf(message);

        if (connection == null) {
            return;
        }

        String closeMessage = null;

        synchronized (connection) {
            if (connection.inFlight.remove(message)
                    && connection.closing
                    && connection.inFlight.isEmpty()) {
                closeMessage = connection.closeMessage;
                connection.closeMessage = null;
            }
        }

        if (closeMessage != null) {
            sendError(connectionId, closeMessage);
        }
    }

    private @Nullable Connection connectionOf(Message<?> message) {
        String connectionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());

        return connectionId == null ? null : connections.get(connectionId);
    }

    private static boolean isError(Message<?> message) {
        return StompCommand.ERROR.equals(
                StompHeaderAccessor.wrap(message).getCommand()
        );
    }

    private static final class Connection {

        private final PlayerPrincipal player;

        // Frames sent to this connection that haven't been written yet,
        // compared by identity: the channel hands the same object to every
        // interceptor callback.
        private final Set<Message<?>> inFlight =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private boolean closing;

        // The ERROR frame's message, until it's sent.
        private @Nullable String closeMessage;

        private Connection(PlayerPrincipal player) {
            this.player = player;
        }
    }
}
