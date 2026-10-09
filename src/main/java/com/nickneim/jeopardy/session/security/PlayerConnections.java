package com.nickneim.jeopardy.session.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open STOMP connections of each player, so the server can close them
 * when the player's token stops working (they left or were kicked). A closed
 * client that reconnects with the same token is then rejected on CONNECT, the
 * same as any other unknown token.
 *
 * Connections are closed by sending a STOMP ERROR frame, which makes Spring
 * close the WebSocket after sending it.
 */
@Component
public class PlayerConnections {

    // STOMP session id (one per connection) -> the player on it.
    private final Map<String, PlayerPrincipal> playersByConnection =
            new ConcurrentHashMap<>();

    private final MessageChannel clientOutboundChannel;

    // The outbound channel is lazy because the broker configuration that
    // creates it also registers PlayerAuthenticationInterceptor, which
    // depends on this.
    public PlayerConnections(
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.clientOutboundChannel = clientOutboundChannel;
    }

    public void connected(String connectionId, PlayerPrincipal player) {
        playersByConnection.put(connectionId, player);
    }

    public void disconnected(String connectionId) {
        playersByConnection.remove(connectionId);
    }

    /** Closes every connection of this player, with {@code message} in the ERROR frame. */
    public void close(PlayerPrincipal player, String message) {
        playersByConnection.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(player)) {
                return false;
            }

            sendError(entry.getKey(), message);
            return true;
        });
    }

    /**
     * Sends an ERROR frame to one connection, which closes it. Never put a
     * token in the message: it could end up in logs.
     */
    public void sendError(String connectionId, String message) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(message);
        error.setSessionId(connectionId);

        clientOutboundChannel.send(
                MessageBuilder.createMessage(new byte[0], error.getMessageHeaders())
        );
    }
}
