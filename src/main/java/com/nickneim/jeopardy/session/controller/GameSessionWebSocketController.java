package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class GameSessionWebSocketController {

    private final GameSessionService gameSessionService;
    private final SimpMessagingTemplate messagingTemplate;

    public GameSessionWebSocketController(
            GameSessionService gameSessionService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.gameSessionService = gameSessionService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/sessions/{sessionId}/join")
    public void joinSession(
            @DestinationVariable UUID sessionId,
            JoinSessionRequest request
    ) {
        GameSession session =
                gameSessionService.getSession(sessionId);

        GameSessionPlayer player =
                new GameSessionPlayer(
                        request.playerId(),
                        request.playerName()
                );

        session.addPlayer(player);

        messagingTemplate.convertAndSend(
                "/topic/sessions/" + sessionId,
                GameSessionResponse.from(session)
        );
    }

}
