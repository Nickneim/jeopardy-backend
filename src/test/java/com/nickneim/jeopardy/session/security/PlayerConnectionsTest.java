package com.nickneim.jeopardy.session.security;

import com.nickneim.jeopardy.session.model.SessionCode;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.atLeast;
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

    private final PlayerPrincipal player = new PlayerPrincipal(SessionCode.random(), 5, 1L);

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
    void frameSentAfterClosingWhileOthersAreInFlight_isStillDelivered() {
        Message<?> inFlight = broadcastTo(CONNECTION);
        connections.preSend(inFlight, clientOutboundChannel);
        connections.close(player, "You left the session");

        Message<?> queued = broadcastTo(CONNECTION);
        assertSame(queued, connections.preSend(queued, clientOutboundChannel));

        connections.afterMessageHandled(inFlight, clientOutboundChannel, handler, null);
        verifyNoInteractions(clientOutboundChannel);

        connections.afterMessageHandled(queued, clientOutboundChannel, handler, null);
        assertErrorSent("You left the session");
    }

    @Test
    void framesSentAfterTheErrorFrame_areDropped_butTheErrorFrameGoesThrough() {
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

    // The setup in WebSocketConfig: publish order preserved, so Spring holds
    // the departure broadcast in its queue while an earlier frame is being
    // written, where it isn't in flight yet. It reaches preSend only after
    // the close, and Spring's ordering interceptor (added after this one)
    // passes it on before this one decides whether the ERROR frame can go.
    @Test
    void departureBroadcastQueuedBehindAnotherFrame_arrivesBeforeTheErrorFrame() {
        Queue<Runnable> writes = new ArrayDeque<>();
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel(writes::add);
        List<Message<?>> written = new ArrayList<>();
        outbound.subscribe(written::add);
        outbound.addInterceptor(new PlayerConnections(outbound));
        connections = (PlayerConnections) outbound.getInterceptors().getFirst();
        OrderedMessageChannelDecorator.configureInterceptor(outbound, true);
        OrderedMessageChannelDecorator ordered =
                new OrderedMessageChannelDecorator(outbound, LogFactory.getLog(getClass()));
        connections.connected(CONNECTION, player);

        Message<?> earlier = broadcastTo(CONNECTION);
        Message<?> departure = broadcastTo(CONNECTION);
        ordered.send(earlier);
        ordered.send(departure);
        connections.close(player, "You left the session");

        while (!writes.isEmpty()) {
            writes.remove().run();
        }

        assertEquals(3, written.size(), written.toString());
        assertSame(earlier, written.get(0));
        assertSame(departure, written.get(1));
        StompHeaderAccessor error = StompHeaderAccessor.wrap(written.get(2));
        assertEquals(StompCommand.ERROR, error.getCommand());
        assertEquals("You left the session", error.getMessage());

        // Nothing goes out after the ERROR frame.
        ordered.send(broadcastTo(CONNECTION));
        assertTrue(writes.isEmpty());
    }

    @Test
    void close_reachesEveryConnectionOfThePlayer_andNoOneElses() {
        PlayerPrincipal secondPlayer = new PlayerPrincipal(player.sessionId(), player.sessionInstance(), 2L);
        connections.connected("ws-2", secondPlayer);
        // The same player again, on another tab.
        connections.connected("ws-3", player);

        connections.close(player, "You left the session");

        assertEquals(List.of(CONNECTION, "ws-3"), errorsSentTo().stream().sorted().toList());
    }

    // Player ids restart at 1 in a new session under a closed one's id.
    @Test
    void close_leavesAPlayerOfAnotherSessionWithTheSameIdsAlone() {
        PlayerPrincipal earlier = new PlayerPrincipal(player.sessionId(), player.sessionInstance() - 1, player.playerId());
        connections.connected("ws-2", earlier);

        connections.close(player, "You left the session");

        assertEquals(List.of(CONNECTION), errorsSentTo());
    }

    @Test
    void close_sendsNothingOnceTheConnectionHasEnded() {
        connections.disconnected(CONNECTION);

        connections.close(player, "You left the session");

        verifyNoInteractions(clientOutboundChannel);
    }

    @Test
    void aConnectionIdRecordedAgain_isClosedOnlyAsItsLatestPlayer() {
        PlayerPrincipal elsewhere = new PlayerPrincipal(SessionCode.random(), 7, 1L);
        connections.connected(CONNECTION, elsewhere);

        connections.close(player, "You left the session");
        verifyNoInteractions(clientOutboundChannel);

        connections.close(elsewhere, "You left the session");
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

    private List<String> errorsSentTo() {
        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel, atLeast(0)).send(sent.capture());

        return sent.getAllValues()
                .stream()
                .map(StompHeaderAccessor::wrap)
                .peek(error -> assertEquals(StompCommand.ERROR, error.getCommand()))
                .map(StompHeaderAccessor::getSessionId)
                .toList();
    }

    private static Message<?> broadcastTo(String connectionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(connectionId);
        accessor.setDestination("/topic/sessions/x");
        // Spring's ordered publishing adds a header, as the broker allows.
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<?> errorTo(String connectionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setSessionId(connectionId);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
