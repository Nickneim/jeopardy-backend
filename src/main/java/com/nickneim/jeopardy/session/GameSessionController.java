package com.nickneim.jeopardy.session;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/game-sessions")
public class GameSessionController {

    private final GameSessionService gameSessionService;

    public GameSessionController(GameSessionService gameSessionService) {
        this.gameSessionService = gameSessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GameSessionResponse createSession() {
        GameSession session = gameSessionService.createSession();

        return new GameSessionResponse(session.getId());
    }

    public record GameSessionResponse(UUID id) {
    }
}
