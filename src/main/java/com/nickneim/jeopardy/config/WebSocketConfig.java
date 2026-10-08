package com.nickneim.jeopardy.config;

import com.nickneim.jeopardy.session.security.PlayerAuthenticationInterceptor;
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
    private final CorsProperties corsProperties;

    public WebSocketConfig(
            PlayerAuthenticationInterceptor playerAuthenticationInterceptor,
            CorsProperties corsProperties
    ) {
        this.playerAuthenticationInterceptor = playerAuthenticationInterceptor;
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
}
