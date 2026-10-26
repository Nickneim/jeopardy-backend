package com.nickneim.jeopardy.config;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Closes a connection the broker drops without sending it a STOMP ERROR
 * frame, so clients can take every ERROR frame to mean their token stopped
 * working.
 *
 * <p>When the simple broker drops a connection (it missed its heartbeats, or
 * the client sent a DISCONNECT without asking for a receipt) it sends a
 * DISCONNECT_ACK, which Spring's StompSubProtocolHandler turns into an ERROR
 * frame ("Session closed.") before closing the WebSocket. A client that only
 * went quiet for a while (a browser tab in the background) would then forget
 * its token and lose its seat. Instead this drops that DISCONNECT_ACK on the
 * client outbound channel and closes the WebSocket itself, so the client sees
 * only the close, as after any dropped connection, and reconnects with the
 * same token. Closing it ends the STOMP session as usual: Spring sends a
 * DISCONNECT inbound, and PlayerConnections marks the player away.
 *
 * <p>The interceptor needs the WebSocket session to close it, so this also
 * decorates the WebSocket handler (WebSocketConfig) to keep every open one by
 * id, which is also the STOMP session id.
 */
@Component
public class SilentDisconnects implements ChannelInterceptor, WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(SilentDisconnects.class);

    static final CloseStatus MISSED_HEARTBEATS = CloseStatus.NORMAL.withReason("Missed heartbeats");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    sessions.remove(session.getId());
                }
            }
        };
    }

    @Override
    public @Nullable Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.DISCONNECT_ACK) {
            return message;
        }

        Message<?> disconnect = (Message<?>) message.getHeaders()
                .get(SimpMessageHeaderAccessor.DISCONNECT_MESSAGE_HEADER);

        // A client that asked for a receipt gets it, and closes the
        // connection itself.
        if (disconnect != null && StompHeaderAccessor.wrap(disconnect).getReceipt() != null) {
            return message;
        }

        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        WebSocketSession session = sessionId == null ? null : sessions.get(sessionId);

        if (session != null) {
            // Without the client's DISCONNECT, the broker dropped the
            // connection for missing its heartbeats.
            close(session, disconnect == null ? MISSED_HEARTBEATS : CloseStatus.NORMAL);
        }

        return null;
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException exception) {
            log.debug("Closing WebSocket session {} failed", session.getId(), exception);
        }
    }
}
