package com.nickneim.jeopardy.session.security;

import com.nickneim.jeopardy.session.model.SessionCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The ERROR frame that closes a departing player's connection must wait until
 * everything already sent to that connection has been written; see
 * PlayerConnections for why.
 */
class PlayerConnectionsTest {

    private static final String CONNECTION = "ws-1";

    private final PlayerPrincipal player = new PlayerPrincipal(SessionCode.random(), 1L);

    private MessageChannel clientOutboundChannel;
    private PlayerConnections connections;
    private final MessageHandler handler = mock(MessageHandler.class);

    @BeforeEach
    void setUp() {
        clientOutboundChannel = mock(MessageChannel.class);
        connections = new PlayerConnections(clientOutboundChannel);
        connections.connected(CONNECTION, player);
    }

    @Test
    void closingAnIdleConnection_sendsTheErrorFrameAtOnce() {
        connections.close(player, "You left the session");

        assertErrorSent("You left the session");
    }

    @Test
    void closingWhileAFrameIsInFlight_waitsUntilItHasBeenWritten() {
        Message<?> broadcast = broadcastTo(CONNECTION);
        assertSame(broadcast, connections.preSend(broadcast, clientOutboundChannel));
        connections.beforeHandle(broadcast, clientOutboundChannel, handler);

        connections.close(player, "You left the session");

        verifyNoInteractions(clientOutboundChannel);

        connections.afterMessageHandled(broadcast, clientOutboundChannel, handler, null);

        assertErrorSent("You left the session");
    }

    @Test
    void errorFrame_waitsForEveryFrameInFlight() {
        Message<?> first = broadcastTo(CONNECTION);
        Message<?> second = broadcastTo(CONNECTION);
        connections.preSend(first, clientOutboundChannel);
        connections.preSend(second, clientOutboundChannel);

        connections.close(player, "You left the session");
        connections.afterMessageHandled(second, clientOutboundChannel, handler, null);

        verifyNoInteractions(clientOutboundChannel);

        connections.afterMessageHandled(first, clientOutboundChannel, handler, new RuntimeException("write failed"));

        assertErrorSent("You left the session");
    }

    @Test
    void framesSentAfterClosing_areDropped_butTheErrorFrameGoesThrough() {
        connections.close(player, "You left the session");

        assertNull(connections.preSend(broadcastTo(CONNECTION), clientOutboundChannel));

        Message<?> error = errorTo(CONNECTION);
        assertSame(error, connections.preSend(error, clientOutboundChannel));
    }

    @Test
    void frameTheExecutorRejected_doesntHoldBackTheErrorFrame() {
        Message<?> broadcast = broadcastTo(CONNECTION);
        connections.preSend(broadcast, clientOutboundChannel);
        connections.close(player, "You left the session");

        connections.afterSendCompletion(broadcast, clientOutboundChannel, false, new RuntimeException("rejected"));

        assertErrorSent("You left the session");
    }

    @Test
    void otherConnections_areNotTracked() {
        Message<?> broadcast = broadcastTo("anonymous");

        assertSame(broadcast, connections.preSend(broadcast, clientOutboundChannel));

        connections.close(player, "You left the session");

        assertErrorSent("You left the session");
    }

    private void assertErrorSent(String message) {
        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(sent.capture());

        StompHeaderAccessor error = StompHeaderAccessor.wrap(sent.getValue());
        assertEquals(StompCommand.ERROR, error.getCommand());
        assertEquals(CONNECTION, error.getSessionId());
        assertEquals(message, error.getMessage());
    }

    private static Message<?> broadcastTo(String connectionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(connectionId);
        accessor.setDestination("/topic/sessions/x");

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<?> errorTo(String connectionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setSessionId(connectionId);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
