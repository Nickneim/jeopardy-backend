package com.nickneim.jeopardy.session.security;

import com.nickneim.jeopardy.session.model.SessionCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor.TOKEN_HEADER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerAuthenticationInterceptorTest {

    private PlayerTokenRegistry registry;
    private MessageChannel clientOutboundChannel;
    private PlayerConnections connections;
    private PlayerAuthenticationInterceptor interceptor;

    private final PlayerPrincipal player =
            new PlayerPrincipal(SessionCode.random(), 3, 42L);

    @BeforeEach
    void setUp() {
        registry = new PlayerTokenRegistry();
        registry.register("valid-token", player);
        clientOutboundChannel = mock(MessageChannel.class);
        connections = new PlayerConnections(clientOutboundChannel, event -> {});
        interceptor = new PlayerAuthenticationInterceptor(registry, connections);
    }

    @Test
    void closingAPlayer_sendsAnErrorFrameToEachOfTheirConnections() {
        interceptor.preSend(message(StompCommand.CONNECT, "valid-token", "ws-1"), null);
        interceptor.preSend(message(StompCommand.CONNECT, "valid-token", "ws-2"), null);
        interceptor.preSend(message(StompCommand.CONNECT, null, "ws-anonymous"), null);

        connections.close(player, "You left the session");

        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel, times(2)).send(sent.capture());

        assertEquals(
                Set.of("ws-1", "ws-2"),
                sent.getAllValues().stream()
                        .map(StompHeaderAccessor::wrap)
                        .peek(error -> assertEquals(StompCommand.ERROR, error.getCommand()))
                        .peek(error -> assertEquals("You left the session", error.getMessage()))
                        .map(StompHeaderAccessor::getSessionId)
                        .collect(Collectors.toSet())
        );
    }

    @Test
    void closedConnection_isForgotten() {
        interceptor.preSend(message(StompCommand.CONNECT, "valid-token", "ws-1"), null);
        interceptor.preSend(message(StompCommand.DISCONNECT, null, "ws-1"), null);

        connections.close(player, "You left the session");

        verifyNoInteractions(clientOutboundChannel);
    }

    @Test
    void closingAPlayer_leavesOtherPlayersConnected() {
        PlayerPrincipal other = new PlayerPrincipal(player.sessionId(), player.sessionInstance(), 43L);
        registry.register("other-token", other);
        interceptor.preSend(message(StompCommand.CONNECT, "other-token", "ws-other"), null);

        connections.close(player, "You left the session");

        verifyNoInteractions(clientOutboundChannel);
    }

    @Test
    void connectWithValidToken_attachesThePlayer() {
        Message<?> result =
                interceptor.preSend(message(StompCommand.CONNECT, "valid-token"), null);

        assertEquals(player, userOf(result));
    }

    @Test
    void connectWithUnknownToken_isDroppedAndAnsweredWithAnErrorFrame() {
        Message<?> result =
                interceptor.preSend(message(StompCommand.CONNECT, "forged-token"), null);

        assertNull(result);

        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(sent.capture());

        StompHeaderAccessor error =
                StompHeaderAccessor.wrap(sent.getValue());
        assertEquals(StompCommand.ERROR, error.getCommand());
        assertEquals("ws-session-1", error.getSessionId());
        assertEquals("Unknown player token", error.getMessage());
        assertFalse(error.toString().contains("forged-token"), error.toString());
    }

    // The player leaves between the token lookup and the connection being
    // recorded, so the departure's close can't have seen this connection.
    @Test
    void connectWhoseTokenIsRevokedDuringTheConnect_isRejected() {
        PlayerTokenRegistry racingRegistry = mock(PlayerTokenRegistry.class);
        when(racingRegistry.find("valid-token"))
                .thenReturn(Optional.of(player))
                .thenReturn(Optional.empty());
        interceptor = new PlayerAuthenticationInterceptor(racingRegistry, connections);

        Message<?> result =
                interceptor.preSend(message(StompCommand.CONNECT, "valid-token"), null);

        assertNull(result);
        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(sent.capture());
        StompHeaderAccessor error = StompHeaderAccessor.wrap(sent.getValue());
        assertEquals(StompCommand.ERROR, error.getCommand());
        assertEquals("ws-session-1", error.getSessionId());

        // Forgotten, so a later close doesn't send a second ERROR frame.
        clearInvocations(clientOutboundChannel);
        connections.close(player, "You were removed from the session by the host");
        verifyNoInteractions(clientOutboundChannel);
    }

    // STOMP 1.2 clients may open with a STOMP frame instead of CONNECT, and
    // Spring accepts either.
    @Test
    void stompFrame_isAuthenticatedLikeConnect() {
        Message<?> valid =
                interceptor.preSend(message(StompCommand.STOMP, "valid-token", "ws-1"), null);

        assertEquals(player, userOf(valid));

        Message<?> unknown =
                interceptor.preSend(message(StompCommand.STOMP, "forged-token", "ws-2"), null);

        assertNull(unknown);
        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(sent.capture());
        StompHeaderAccessor error = StompHeaderAccessor.wrap(sent.getValue());
        assertEquals(StompCommand.ERROR, error.getCommand());
        assertEquals("ws-2", error.getSessionId());
        assertEquals("Unknown player token", error.getMessage());
    }

    @Test
    void connectWithoutToken_staysAnonymous() {
        Message<?> result =
                interceptor.preSend(message(StompCommand.CONNECT, null), null);

        assertNull(userOf(result));
        verifyNoInteractions(clientOutboundChannel);
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
        return message(command, token, "ws-session-1");
    }

    private Message<byte[]> message(StompCommand command, String token, String connectionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);

        if (token != null) {
            accessor.setNativeHeader(TOKEN_HEADER, token);
        }

        accessor.setSessionId(connectionId);

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
