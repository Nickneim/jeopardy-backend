package com.nickneim.jeopardy.session;

import com.nickneim.jeopardy.entity.GameRound;
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

    public record JoinSessionRequest(
            Long playerId,
            String playerName
    ) {}

    public record GameSessionResponse(
            UUID id,
            GameRound round,
            GameSessionState state,
            java.util.List<PlayerResponse> players
    ) {
        static GameSessionResponse from(GameSession session) {
            return new GameSessionResponse(
                    session.getId(),
                    session.getRound(),
                    session.getState(),
                    session.getPlayers().stream()
                            .map(player -> new PlayerResponse(
                                    player.getId(),
                                    player.getName(),
                                    player.getScore()
                            ))
                            .toList()
            );
        }
    }

    public record PlayerResponse(
            Long id,
            String name,
            int score
    ) {}
}
