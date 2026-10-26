package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.dto.CreateGameSessionRequest;
import com.nickneim.jeopardy.session.dto.CreateGameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.dto.JoinSessionRequest;
import com.nickneim.jeopardy.session.dto.JoinSessionResponse;
import com.nickneim.jeopardy.session.lobby.SessionLobby;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.service.GameBoardService;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/game-sessions")
@Validated
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final GameBoardService gameBoardService;
    private final SessionLobby sessionLobby;

    public GameSessionController(
            GameSessionService gameSessionService,
            GameBoardService gameBoardService,
            SessionLobby sessionLobby
    ) {
        this.gameSessionService = gameSessionService;
        this.gameBoardService = gameBoardService;
        this.sessionLobby = sessionLobby;
    }

    /**
     * Sessions to join or watch, for a lobby page, from every instance.
     * Finished sessions are left out unless {@code includeFinished}.
     * Summaries hold no secrets.
     */
    @GetMapping
    public PageResponse<GameSessionSummaryResponse> listSessions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            // Boolean, not boolean, so a bad value gets ApiExceptionHandler's message.
            @RequestParam(defaultValue = "false") Boolean includeFinished
    ) {
        return sessionLobby.listSessions(includeFinished, page, size);
    }

    /**
     * Creates a session under a code the client chose at random (409 if it's
     * taken, and the client tries another). With the id known up front, a load balancer in front of several
     * instances can send this request, like every later one about the
     * session, to the instance that will hold it (docs/api.md §3.2).
     */
    @PutMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameSessionResponse createSession(
            @PathVariable SessionCode sessionId,
            @Valid @RequestBody CreateGameSessionRequest request
    ) {
        // Checked first, so a taken code (which the client retries) doesn't
        // load the whole game for nothing. createSession still guards a race.
        gameSessionService.requireUnusedId(sessionId);
        GameBoard board = gameBoardService.loadBoard(request.gameId());
        GameSession session = gameSessionService.createSession(sessionId, board);

        return CreateGameSessionResponse.from(session);
    }

    /**
     * The session's current state, the same as the latest broadcast. Lets a
     * client that just connected, refreshed or reconnected catch up without
     * waiting for the next change. Contains no secrets, like the broadcast.
     */
    @GetMapping("/{sessionId}")
    public GameSessionResponse getSession(
            @PathVariable SessionCode sessionId
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
            @PathVariable SessionCode sessionId,
            @Valid @RequestBody JoinSessionRequest request
    ) {
        GameSessionPlayer player =
                gameSessionService.joinSession(sessionId, request.playerName());

        return new JoinSessionResponse(player.getId(), player.getToken());
    }

}
