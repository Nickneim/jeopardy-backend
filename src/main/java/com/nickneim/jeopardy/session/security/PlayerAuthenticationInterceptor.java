package com.nickneim.jeopardy.session.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
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
 */
@Component
public class PlayerAuthenticationInterceptor implements ChannelInterceptor {

    public static final String TOKEN_HEADER = "player-token";

    static final String UNKNOWN_TOKEN_MESSAGE = "Unknown player token";

    private final PlayerTokenRegistry playerTokens;
    private final MessageChannel clientOutboundChannel;

    // The outbound channel is lazy because the broker configuration that
    // creates it also registers this interceptor.
    public PlayerAuthenticationInterceptor(
            PlayerTokenRegistry playerTokens,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.playerTokens = playerTokens;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String token = accessor.getFirstNativeHeader(TOKEN_HEADER);

        if (token == null) {
            return message;
        }

        PlayerPrincipal player = playerTokens.find(token).orElse(null);

        if (player == null) {
            reject(accessor.getSessionId());
            return null;
        }

        accessor.setUser(player);

        return message;
    }

    // Never include the token in the error: it could end up in logs.
    private void reject(String sessionId) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(UNKNOWN_TOKEN_MESSAGE);
        error.setSessionId(sessionId);

        clientOutboundChannel.send(
                MessageBuilder.createMessage(new byte[0], error.getMessageHeaders())
        );
    }
}
