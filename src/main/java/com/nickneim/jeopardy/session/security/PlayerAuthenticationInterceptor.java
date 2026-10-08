package com.nickneim.jeopardy.session.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
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
 */
@Component
public class PlayerAuthenticationInterceptor implements ChannelInterceptor {

    public static final String TOKEN_HEADER = "player-token";

    private final PlayerTokenRegistry playerTokens;

    public PlayerAuthenticationInterceptor(PlayerTokenRegistry playerTokens) {
        this.playerTokens = playerTokens;
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

        // Never include the token in the error: it could end up in logs.
        PlayerPrincipal player = playerTokens.find(token)
                .orElseThrow(() -> new MessagingException(message, "Unknown player token"));

        accessor.setUser(player);

        return message;
    }
}
