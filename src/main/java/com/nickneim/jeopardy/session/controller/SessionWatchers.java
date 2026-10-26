package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Whether anyone, player or spectator, is subscribed to a session's topic,
 * asked of the broker itself, so unsubscribing and disconnecting are always
 * accounted for. A closed session's code stays taken while someone is: a
 * spectator still showing the closed game would otherwise start receiving
 * the broadcasts of an unrelated session that reused its code.
 */
@Component
public class SessionWatchers {

    // A provider, because the broker configuration that creates the broker
    // also needs the beans that depend on this.
    private final ObjectProvider<AbstractBrokerMessageHandler> broker;

    public SessionWatchers(
            @Qualifier("simpleBrokerMessageHandler") ObjectProvider<AbstractBrokerMessageHandler> broker
    ) {
        this.broker = broker;
    }

    public boolean hasWatchers(SessionCode sessionId) {
        if (!(broker.getIfAvailable() instanceof SimpleBrokerMessageHandler simpleBroker)) {
            return false;
        }

        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(GameSessionBroadcaster.topic(sessionId));
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());

        return !simpleBroker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty();
    }
}
