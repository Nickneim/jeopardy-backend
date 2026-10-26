package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class GameSessionBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public GameSessionBroadcaster(
            SimpMessagingTemplate messagingTemplate
    ) {
        this.messagingTemplate = messagingTemplate;
    }

    /** Where a session's state is broadcast. */
    public static String topic(SessionCode sessionId) {
        return "/topic/sessions/" + sessionId;
    }

    public void broadcast(GameSession session) {
        messagingTemplate.convertAndSend(
                topic(session.getId()),
                GameSessionResponse.from(session)
        );
    }
}
