package com.nickneim.jeopardy.config;

import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    private final PlayerAuthenticationInterceptor playerAuthenticationInterceptor;
    private final PlayerConnections playerConnections;
    private final CorsProperties corsProperties;

    public WebSocketConfig(
            PlayerAuthenticationInterceptor playerAuthenticationInterceptor,
            PlayerConnections playerConnections,
            CorsProperties corsProperties
    ) {
        this.playerAuthenticationInterceptor = playerAuthenticationInterceptor;
        this.playerConnections = playerConnections;
        this.corsProperties = corsProperties;
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
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
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
