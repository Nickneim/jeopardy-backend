package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.JoinSessionResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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

    /**
     * Joins a session. The response, including the player's secret token,
     * goes only to the caller; everyone else learns about the new player
     * from the session broadcast, which never contains tokens.
     */
    @PostMapping("/{sessionId}/players")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinSessionResponse joinSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody JoinSessionRequest request
    ) {
        GameSessionPlayer player =
                gameSessionService.joinSession(sessionId, request.playerName());

        return new JoinSessionResponse(player.getId(), player.getToken());
    }

}
