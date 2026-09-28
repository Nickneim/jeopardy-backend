package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.model.GameSession;
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

    public void broadcast(GameSession session) {
        messagingTemplate.convertAndSend(
                "/topic/sessions/" + session.getId(),
                GameSessionResponse.from(session)
        );
    }
}