package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.service.GameSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/game-sessions")
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final GameService gameService;

    public GameSessionController(GameSessionService gameSessionService, GameService gameService) {
        this.gameSessionService = gameSessionService;
        this.gameService = gameService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameSessionResponse createSession(
            @Valid @RequestBody CreateGameSessionRequest request
    ) {
        Game game = gameService.getGame(request.gameId());
        GameSession session = gameSessionService.createSession(game);

        return new CreateGameSessionResponse(session.getId());
    }

}
