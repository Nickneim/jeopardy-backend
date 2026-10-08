package com.nickneim.jeopardy.session.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.Optional;
import java.util.UUID;

import static com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor.TOKEN_HEADER;
import static org.junit.jupiter.api.Assertions.*;

class PlayerAuthenticationInterceptorTest {

    private PlayerTokenRegistry registry;
    private PlayerAuthenticationInterceptor interceptor;

    private final PlayerPrincipal player =
            new PlayerPrincipal(UUID.randomUUID(), 42L);

    @BeforeEach
    void setUp() {
        registry = new PlayerTokenRegistry();
        registry.register("valid-token", player);
        interceptor = new PlayerAuthenticationInterceptor(registry);
    }

    @Test
    void connectWithValidToken_attachesThePlayer() {
        Message<?> result =
                interceptor.preSend(message(StompCommand.CONNECT, "valid-token"), null);

        assertEquals(player, userOf(result));
    }

    @Test
    void connectWithUnknownToken_isRejectedWithoutRevealingTheToken() {
        MessagingException exception = assertThrows(
                MessagingException.class,
                () -> interceptor.preSend(message(StompCommand.CONNECT, "forged-token"), null)
        );

        assertFalse(exception.getMessage().contains("forged-token"), exception.getMessage());
    }

    @Test
    void connectWithoutToken_staysAnonymous() {
        Message<?> result =
                interceptor.preSend(message(StompCommand.CONNECT, null), null);

        assertNull(userOf(result));
    }

    @Test
    void tokenOnOtherFrames_isIgnored() {
        Message<?> result =
                interceptor.preSend(message(StompCommand.SEND, "valid-token"), null);

        assertNull(userOf(result));
    }

    @Test
    void registry_findsRegisteredTokensOnly() {
        assertEquals(Optional.of(player), registry.find("valid-token"));
        assertEquals(Optional.empty(), registry.find("forged-token"));
        assertEquals(Optional.empty(), registry.find(null));

        registry.unregister("valid-token");

        assertEquals(Optional.empty(), registry.find("valid-token"));
    }

    private Message<byte[]> message(StompCommand command, String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);

        if (token != null) {
            accessor.setNativeHeader(TOKEN_HEADER, token);
        }

        // Keeps the headers mutable, as Spring does for inbound messages.
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Object userOf(Message<?> message) {
        return MessageHeaderAccessor
                .getAccessor(message, StompHeaderAccessor.class)
                .getUser();
    }
}
