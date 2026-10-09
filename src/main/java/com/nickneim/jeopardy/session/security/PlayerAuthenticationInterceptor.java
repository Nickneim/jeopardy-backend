package com.nickneim.jeopardy.session.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Identifies a player once, when their STOMP connection opens, from the
 * secret token in the {@value #TOKEN_HEADER} CONNECT header. The player is
 * attached to the connection as a {@link PlayerPrincipal}, so later messages
 * never need to say who sent them.
 *
 * <ul>
 *     <li>No token: the connection is anonymous (it can watch, not act).</li>
 *     <li>Unknown token: the connection is rejected, so a client bug fails
 *     loudly instead of silently acting as a spectator.</li>
 * </ul>
 *
 * A rejection can't be thrown: with ordered message handling
 * (WebSocketConfig's setPreserveReceiveOrder) Spring catches and only logs
 * exceptions from inbound interceptors, so no ERROR frame would reach the
 * client. Instead this sends the ERROR frame itself, which also makes Spring
 * close the connection, and drops the CONNECT.
 *
 * It also records each player's connections in {@link PlayerConnections}, so
 * they can be closed when the player leaves.
 */
@Component
public class PlayerAuthenticationInterceptor implements ChannelInterceptor {

    public static final String TOKEN_HEADER = "player-token";

    static final String UNKNOWN_TOKEN_MESSAGE = "Unknown player token";

    private final PlayerTokenRegistry playerTokens;
    private final PlayerConnections connections;

    public PlayerAuthenticationInterceptor(
            PlayerTokenRegistry playerTokens,
            PlayerConnections connections
    ) {
        this.playerTokens = playerTokens;
        this.connections = connections;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        // Spring sends a DISCONNECT for every connection that ends, even
        // when the client just went away.
        if (accessor.getCommand() == StompCommand.DISCONNECT) {
            connections.disconnected(accessor.getSessionId());
            return message;
        }

        if (accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String token = accessor.getFirstNativeHeader(TOKEN_HEADER);

        if (token == null) {
            return message;
        }

        PlayerPrincipal player = playerTokens.find(token).orElse(null);

        // Never include the token in the error: it could end up in logs.
        if (player == null) {
            connections.sendError(accessor.getSessionId(), UNKNOWN_TOKEN_MESSAGE);
            return null;
        }

        accessor.setUser(player);
        connections.connected(accessor.getSessionId(), player);

        return message;
    }
}
