package com.nickneim.jeopardy.session.security;

import com.nickneim.jeopardy.session.model.SessionCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.HashSet;
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
 * connection lets the frames in flight finish (so the departure broadcast
 * still arrives), and once none is left sends the ERROR frame, which is then
 * the only thing being written to that connection. Frames sent after the
 * ERROR frame are dropped.
 *
 * <p>Frames are only dropped after the ERROR frame, not from the close on,
 * because with publish order preserved (WebSocketConfig) Spring holds a
 * connection's later frames in a queue in front of the channel: the
 * departure broadcast may still be waiting there behind an earlier frame
 * when the connection is closed, and reaches this interceptor only after
 * that. It isn't lost to the ERROR frame either: Spring's ordering
 * interceptor is added to the channel after this one, so its
 * afterMessageHandled runs first and passes the next queued frame on (into
 * {@code inFlight}) before this one checks whether any frame is left. A
 * frame sent after the close but before the ERROR frame is delivered too,
 * since it can't be told apart from one sent before.
 *
 * <p>Every player connection that opens or closes is also announced as a
 * {@link PlayerConnectionsChanged} event, so the session can show who is away.
 */
@Component
public class PlayerConnections implements ExecutorChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PlayerConnections.class);

    // STOMP session id (one per connection) -> the connection.
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    // Session id -> the ids of its players' connections, so closing one
    // player's connections doesn't look through everyone's; a session with
    // none has no entry. The sets are only touched inside the map's compute
    // calls.
    private final Map<SessionCode, Set<String>> connectionsBySession = new ConcurrentHashMap<>();

    private final MessageChannel clientOutboundChannel;
    private final ApplicationEventPublisher events;

    // The outbound channel is lazy because the broker configuration that
    // creates it also registers this as its interceptor.
    public PlayerConnections(
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
            ApplicationEventPublisher events
    ) {
        this.clientOutboundChannel = clientOutboundChannel;
        this.events = events;
    }

    public void connected(String connectionId, PlayerPrincipal player) {
        Connection previous = connections.put(connectionId, new Connection(player));

        if (previous != null) {
            forget(connectionId, previous);
        }
        connectionsBySession.compute(player.sessionId(), (sessionId, ids) -> {
            Set<String> updated = ids == null ? new HashSet<>() : ids;
            updated.add(connectionId);
            return updated;
        });

        announce(player);
    }

    public void disconnected(String connectionId) {
        Connection connection = connections.remove(connectionId);

        if (connection != null) {
            forget(connectionId, connection);
            announce(connection.player);
        }
    }

    /** Whether the player has any open connection. */
    public boolean isConnected(PlayerPrincipal player) {
        return !connectionsOf(player).isEmpty();
    }

    // Called from the inbound channel's interceptor, which must never throw
    // (see PlayerAuthenticationInterceptor).
    private void announce(PlayerPrincipal player) {
        try {
            events.publishEvent(new PlayerConnectionsChanged(player));
        } catch (RuntimeException exception) {
            log.error("Handling a connection change of player {} failed", player.getName(), exception);
        }
    }

    private void forget(String connectionId, Connection connection) {
        connectionsBySession.computeIfPresent(connection.player.sessionId(), (sessionId, ids) -> {
            ids.remove(connectionId);
            return ids.isEmpty() ? null : ids;
        });
    }

    /**
     * Closes every connection of this player, with {@code message} in the
     * ERROR frame. Frames already sent to them are delivered first; anything
     * sent after the ERROR frame is dropped.
     */
    public void close(PlayerPrincipal player, String message) {
        List<String> readyToClose = new ArrayList<>();

        for (String connectionId : connectionsOf(player)) {
            Connection connection = connections.get(connectionId);

            if (connection == null) {
                continue;
            }

            synchronized (connection) {
                if (connection.closing) {
                    continue;
                }

                connection.closing = true;

                if (connection.inFlight.isEmpty()) {
                    connection.errorSent = true;
                    readyToClose.add(connectionId);
                } else {
                    connection.closeMessage = message;
                }
            }
        }

        readyToClose.forEach(connectionId -> sendError(connectionId, message));
    }

    // The ids of the player's open connections.
    private List<String> connectionsOf(PlayerPrincipal player) {
        List<String> sessionConnections = new ArrayList<>();
        connectionsBySession.computeIfPresent(player.sessionId(), (sessionId, ids) -> {
            sessionConnections.addAll(ids);
            return ids;
        });

        return sessionConnections.stream()
                .filter(connectionId -> {
                    Connection connection = connections.get(connectionId);
                    return connection != null && connection.player.equals(player);
                })
                .toList();
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
            if (connection.errorSent) {
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
        Connection connection = connectionId == null ? null : connections.get(connectionId);

        if (connection == null) {
            return;
        }

        String closeMessage = null;

        synchronized (connection) {
            if (connection.inFlight.remove(message)
                    && connection.closing
                    && !connection.errorSent
                    && connection.inFlight.isEmpty()) {
                closeMessage = connection.closeMessage;
                connection.closeMessage = null;
                connection.errorSent = true;
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

        // close was called.
        private boolean closing;

        // The ERROR frame has been sent; later frames are dropped.
        private boolean errorSent;

        // The ERROR frame's message, until it's sent.
        private @Nullable String closeMessage;

        private Connection(PlayerPrincipal player) {
            this.player = player;
        }
    }
}
