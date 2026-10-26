package com.nickneim.jeopardy.config;

import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(CorsProperties.class)
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    /**
     * How often (ms) the server sends a heartbeat, and how often it asks the
     * client for one. Each side uses the larger of its own value and the
     * other's, and the broker drops a connection that sends nothing for three
     * of the client's intervals.
     */
    public static final long HEARTBEAT_MILLIS = 10_000;

    private final PlayerAuthenticationInterceptor playerAuthenticationInterceptor;
    private final PlayerConnections playerConnections;
    private final CorsProperties corsProperties;
    private final TaskScheduler messageBrokerTaskScheduler;

    // The scheduler is lazy because the broker configuration that creates it
    // also calls this configurer.
    public WebSocketConfig(
            PlayerAuthenticationInterceptor playerAuthenticationInterceptor,
            PlayerConnections playerConnections,
            CorsProperties corsProperties,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler
    ) {
        this.playerAuthenticationInterceptor = playerAuthenticationInterceptor;
        this.playerConnections = playerConnections;
        this.corsProperties = corsProperties;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {
        // Browsers send an Origin header on the WebSocket handshake, and
        // Spring only accepts its own origin unless told otherwise.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(corsProperties.allowedOriginsArray());

        // The inbound channel runs on a thread pool, so without this two
        // messages from one client can be handled out of order: a player's
        // second Final Jeopardy wager could be overwritten by their first.
        // Order is kept per connection, not across connections.
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry
    ) {
        // Without heartbeats a connection that dies without closing (the
        // client lost its network) is never noticed while nothing is written
        // to it, as after its session closed: PlayerConnections would keep it,
        // and with it the session's code, forever. Heartbeats also keep idle
        // connections alive through proxies that time them out.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // The outbound channel runs on a thread pool too, so without this the
        // frames to one client can be written out of order: a broadcast
        // before the one it replaced, or an error before the broadcast of
        // the change that caused it. Order is kept per connection.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {
        registration.interceptors(playerAuthenticationInterceptor);
    }

    // Lets PlayerConnections hold back a departing player's ERROR frame until
    // everything already sent to them has been written, so it isn't lost.
    @Override
    public void configureClientOutboundChannel(
            ChannelRegistration registration
    ) {
        registration.interceptors(playerConnections);
    }
}
