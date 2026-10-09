package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.JoinSessionResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionTimeouts;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/game-sessions")
@Validated
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final GameBoardService gameBoardService;

    public GameSessionController(GameSessionService gameSessionService, GameBoardService gameBoardService) {
        this.gameSessionService = gameSessionService;
        this.gameBoardService = gameBoardService;
    }

    /**
     * Sessions to join or watch, for a lobby page. Finished sessions are left
     * out unless {@code includeFinished}. Summaries hold no secrets.
     */
    @GetMapping
    public PageResponse<GameSessionSummaryResponse> listSessions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            // Boolean, not boolean, so a bad value gets ApiExceptionHandler's message.
            @RequestParam(defaultValue = "false") Boolean includeFinished
    ) {
        return gameSessionService.listSessions(includeFinished, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameSessionResponse createSession(
            @Valid @RequestBody CreateGameSessionRequest request
    ) {
        GameBoard board = gameBoardService.loadBoard(request.gameId());
        GameSessionTimeouts timeouts = request.timers() == null
                ? GameSessionTimeouts.defaults()
                : request.timers().applyTo(GameSessionTimeouts.defaults());
        GameSession session = gameSessionService.createSession(board, timeouts);

        return new CreateGameSessionResponse(session.getId());
    }

    /**
     * The session's current state, the same as the latest broadcast. Lets a
     * client that just connected, refreshed or reconnected catch up without
     * waiting for the next change. Contains no secrets, like the broadcast.
     */
    @GetMapping("/{sessionId}")
    public GameSessionResponse getSession(
            @PathVariable UUID sessionId
    ) {
        return gameSessionService.getSessionState(sessionId);
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
