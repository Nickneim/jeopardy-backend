package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Whether anyone, player or spectator, is subscribed to a session's topic,
 * asked of the broker itself, so unsubscribing and disconnecting are always
 * accounted for. A closed session's code stays taken while someone is: a
 * spectator still showing the closed game would otherwise start receiving
 * the broadcasts of an unrelated session that reused its code.
 *
 * <p>Only the simple broker can be asked. With any other (a STOMP relay to an
 * external broker, say) the application fails to start, rather than silently
 * letting codes be reused under their spectators.
 */
@Component
public class SessionWatchers implements SmartInitializingSingleton {

    // A provider, because the broker configuration that creates the broker
    // also needs the beans that depend on this.
    private final ObjectProvider<AbstractBrokerMessageHandler> broker;

    private SubscriptionRegistry subscriptions;

    public SessionWatchers(
            @Qualifier("simpleBrokerMessageHandler") ObjectProvider<AbstractBrokerMessageHandler> broker
    ) {
        this.broker = broker;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!(broker.getIfAvailable() instanceof SimpleBrokerMessageHandler simpleBroker)) {
            throw new IllegalStateException(
                    "SessionWatchers needs the simple broker to see who watches a session; found "
                            + broker.getIfAvailable()
            );
        }

        subscriptions = simpleBroker.getSubscriptionRegistry();
    }

    public boolean hasWatchers(SessionCode sessionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(GameSessionBroadcaster.topic(sessionId));
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());

        return !subscriptions.findSubscriptions(probe).isEmpty();
    }
}
