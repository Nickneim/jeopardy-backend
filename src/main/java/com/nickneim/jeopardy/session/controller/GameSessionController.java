package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/game-sessions")
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final GameBoardService gameBoardService;

    public GameSessionController(GameSessionService gameSessionService, GameBoardService gameBoardService) {
        this.gameSessionService = gameSessionService;
        this.gameBoardService = gameBoardService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameSessionResponse createSession(
            @Valid @RequestBody CreateGameSessionRequest request
    ) {
        GameBoard board = gameBoardService.loadBoard(request.gameId());
        GameSession session = gameSessionService.createSession(board);

        return new CreateGameSessionResponse(session.getId());
    }

}
