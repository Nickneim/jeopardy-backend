package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.*;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import jakarta.validation.Valid;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;

import java.util.stream.Collectors;

/**
 * Player actions over STOMP. Who sent an action always comes from the
 * connection (attached by PlayerAuthenticationInterceptor from the player's
 * token), never from the message, so a player can't act as someone else.
 * Players join over REST (GameSessionController), which returns the token.
 */
@Controller
public class GameSessionWebSocketController {

    private final GameSessionService gameSessionService;

    public GameSessionWebSocketController(
            GameSessionService gameSessionService
    ) {
        this.gameSessionService = gameSessionService;
    }

    @MessageMapping("/sessions/{sessionId}/start")
    public void startGame(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.startGame(
                sessionId,
                playerId(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/choose-clue")
    public void chooseClue(
            @DestinationVariable SessionCode sessionId,
            @Valid ChooseClueRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.chooseClue(
                sessionId,
                playerId(sessionId, headers),
                request.clueId()
        );
    }

    @MessageMapping("/sessions/{sessionId}/buzz")
    public void buzz(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.buzz(
                sessionId,
                playerId(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/answer")
    public void answerClue(
            @DestinationVariable SessionCode sessionId,
            @Valid AnswerClueRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.answerClue(
                sessionId,
                playerId(sessionId, headers),
                request.answer()
        );
    }

    @MessageMapping("/sessions/{sessionId}/place-daily-double-wager")
    public void placeDailyDoubleWager(
            @DestinationVariable SessionCode sessionId,
            @Valid PlaceDailyDoubleWagerRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.placeDailyDoubleWager(
                sessionId,
                playerId(sessionId, headers),
                request.wager()
        );
    }

    @MessageMapping("/sessions/{sessionId}/place-final-jeopardy-wager")
    public void placeFinalJeopardyWager(
            @DestinationVariable SessionCode sessionId,
            @Valid PlaceFinalJeopardyWagerRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.placeFinalJeopardyWager(
                sessionId,
                playerId(sessionId, headers),
                request.wager()
        );
    }

    @MessageMapping("/sessions/{sessionId}/answer-final-jeopardy")
    public void answerFinalJeopardy(
            @DestinationVariable SessionCode sessionId,
            @Valid AnswerFinalJeopardyRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.answerFinalJeopardy(
                sessionId,
                playerId(sessionId, headers),
                request.answer()
        );
    }

    @MessageMapping("/sessions/{sessionId}/update-timers")
    public void updateTimers(
            @DestinationVariable SessionCode sessionId,
            @Valid SessionTimersRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.updateTimers(
                sessionId,
                playerId(sessionId, headers),
                request
        );
    }

    @MessageMapping("/sessions/{sessionId}/update-settings")
    public void updateSettings(
            @DestinationVariable SessionCode sessionId,
            @Valid SessionSettingsRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.updateSettings(
                sessionId,
                playerId(sessionId, headers),
                request
        );
    }

    @MessageMapping("/sessions/{sessionId}/leave")
    public void leave(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.leave(
                sessionId,
                playerId(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/kick")
    public void kick(
            @DestinationVariable SessionCode sessionId,
            @Valid KickPlayerRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.kick(
                sessionId,
                playerId(sessionId, headers),
                request.playerId()
        );
    }

    /**
     * The id of the player on this connection, checked against the session
     * the action is for. An anonymous connection (no token) can only watch.
     */
    private Long playerId(SessionCode sessionId, SimpMessageHeaderAccessor headers) {
        if (!(headers.getUser() instanceof PlayerPrincipal player)) {
            throw new InvalidGameActionException(
                    GameErrorCode.UNAUTHENTICATED,
                    "Join the session and connect with your player token to play!"
            );
        }

        if (!player.sessionId().equals(sessionId)) {
            throw new InvalidGameActionException(
                    GameErrorCode.PLAYER_NOT_IN_SESSION,
                    "You're not in this session!"
            );
        }

        return player.playerId();
    }

    // Also a missing session: GameSessionNotFoundException is one.
    @MessageExceptionHandler(InvalidGameActionException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleInvalidGameAction(
            InvalidGameActionException exception
    ) {
        return new GameSessionErrorResponse(
                exception.getCode().name(),
                exception.getMessage()
        );
    }

    // A destination with a malformed session code, such as /app/sessions/abc/buzz:
    // converting a destination variable fails with this, unwrapped.
    @MessageExceptionHandler(ConversionFailedException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleInvalidSessionCode(
            ConversionFailedException exception
    ) {
        if (exception.getTargetType().getType() != SessionCode.class) {
            throw exception;
        }

        return new GameSessionErrorResponse(
                GameErrorCode.INVALID_REQUEST.name(),
                "Invalid session code: expected " + SessionCode.LENGTH + " letters and digits"
        );
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleInvalidRequest(
            MethodArgumentNotValidException exception
    ) {
        BindingResult bindingResult = exception.getBindingResult();

        String message = bindingResult == null
                ? "Invalid request!"
                : bindingResult.getFieldErrors()
                        .stream()
                        .map(error -> error.getField() + " " + error.getDefaultMessage())
                        .collect(Collectors.joining(", "));

        return new GameSessionErrorResponse(
                GameErrorCode.INVALID_REQUEST.name(),
                message
        );
    }

}
