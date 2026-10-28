package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Notices a player subscribing to /user/queue/trebek, so Trebek gets their
 * private view at once (after a reconnect or a refresh) instead of at the
 * next change. It intercepts the broker channel, after the broker has
 * registered the subscription: Spring's SessionSubscribeEvent comes before
 * that, and a view sent then could be lost. GameSessionService decides
 * whether the subscriber is Trebek ({@link TrebekViewRequested}).
 */
@Component
public class TrebekSubscriptions implements ChannelInterceptor {

    // A user destination reaches the broker as /queue/trebek-user<connection id>.
    private static final String SUBSCRIBED_DESTINATION_PREFIX = GameSessionBroadcaster.TREBEK_QUEUE + "-user";

    private final ApplicationEventPublisher events;

    public TrebekSubscriptions(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** A player subscribed to Trebek's private view. */
    public record TrebekViewRequested(PlayerPrincipal player) {}

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception exception) {
        if (!sent || exception != null
                || SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.SUBSCRIBE) {
            return;
        }

        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());

        if (destination != null && destination.startsWith(SUBSCRIBED_DESTINATION_PREFIX)
                && SimpMessageHeaderAccessor.getUser(message.getHeaders()) instanceof PlayerPrincipal player) {
            events.publishEvent(new TrebekViewRequested(player));
        }
    }
}
