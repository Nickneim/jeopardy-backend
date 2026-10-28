package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.*;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.util.stream.Collectors;

/**
 * Player actions over STOMP. Who sent an action always comes from the
 * connection (attached by PlayerAuthenticationInterceptor from the player's
 * token), never from the message, so a player can't act as someone else.
 * Players join over REST (GameSessionController), which returns the token.
 */
@Controller
public class GameSessionWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(GameSessionWebSocketController.class);

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
                player(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/choose-clue")
    public void chooseClue(
            @DestinationVariable SessionCode sessionId,
            @Valid ChooseClueRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.chooseClue(
                player(sessionId, headers),
                request.clueId()
        );
    }

    @MessageMapping("/sessions/{sessionId}/buzz")
    public void buzz(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.buzz(
                player(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/answer")
    public void answerClue(
            @DestinationVariable SessionCode sessionId,
            @Valid AnswerClueRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.answerClue(
                player(sessionId, headers),
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
                player(sessionId, headers),
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
                player(sessionId, headers),
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
                player(sessionId, headers),
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
                player(sessionId, headers),
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
                player(sessionId, headers),
                request
        );
    }

    @MessageMapping("/sessions/{sessionId}/leave")
    public void leave(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.leave(
                player(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/kick")
    public void kick(
            @DestinationVariable SessionCode sessionId,
            @Valid KickPlayerRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.kick(
                player(sessionId, headers),
                request.playerId()
        );
    }

    // -------------------------------------------------------------------------
    // Trebek's actions
    // -------------------------------------------------------------------------

    @MessageMapping("/sessions/{sessionId}/advance")
    public void advance(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.advance(
                player(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/return-clue")
    public void returnClue(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.returnClue(
                player(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/rule")
    public void rule(
            @DestinationVariable SessionCode sessionId,
            @Valid RuleRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.rule(
                player(sessionId, headers),
                request.correct()
        );
    }

    @MessageMapping("/sessions/{sessionId}/rule-final")
    public void ruleFinal(
            @DestinationVariable SessionCode sessionId,
            @Valid RuleFinalRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.ruleFinal(
                player(sessionId, headers),
                request.playerId(),
                request.correct()
        );
    }

    @MessageMapping("/sessions/{sessionId}/adjust-score")
    public void adjustScore(
            @DestinationVariable SessionCode sessionId,
            @Valid AdjustScoreRequest request,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.adjustScore(
                player(sessionId, headers),
                request.playerId(),
                request.amount()
        );
    }

    @MessageMapping("/sessions/{sessionId}/pause")
    public void pause(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.pause(
                player(sessionId, headers)
        );
    }

    @MessageMapping("/sessions/{sessionId}/resume")
    public void resume(
            @DestinationVariable SessionCode sessionId,
            SimpMessageHeaderAccessor headers
    ) {
        gameSessionService.resume(
                player(sessionId, headers)
        );
    }

    /**
     * The player on this connection, checked against the session the action
     * is for (the service checks it's that very session, not an earlier one
     * with the same id). An anonymous connection (no token) can only watch.
     */
    private PlayerPrincipal player(SessionCode sessionId, SimpMessageHeaderAccessor headers) {
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

        return player;
    }

    // Also a missing session: GameSessionNotFoundException is one.
    @MessageExceptionHandler(InvalidGameActionException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleInvalidGameAction(
            InvalidGameActionException exception
    ) {
        return new GameSessionErrorResponse(
                exception.getCode(),
                exception.getMessage()
        );
    }

    // A destination with a malformed session code, such as /app/sessions/abc/buzz:
    // converting a destination variable fails with this, unwrapped. Every
    // destination variable is a session code today; any other is still the
    // client's mistake. (Rethrowing wouldn't reach handleUnexpected: Spring
    // only logs what an exception handler throws, and the sender gets no reply.)
    @MessageExceptionHandler(ConversionFailedException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleInvalidSessionCode(
            ConversionFailedException exception
    ) {
        if (exception.getTargetType().getType() != SessionCode.class) {
            return new GameSessionErrorResponse(
                    GameErrorCode.INVALID_REQUEST,
                    "Invalid request: a value in the destination has the wrong format"
            );
        }

        return new GameSessionErrorResponse(
                GameErrorCode.INVALID_REQUEST,
                "Invalid session code: expected " + SessionCode.LENGTH + " letters and digits"
        );
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleInvalidRequest(
            MethodArgumentNotValidException exception
    ) {
        BindingResult bindingResult = exception.getBindingResult();

        // Field errors name their field. An empty body isn't one: it's a
        // global error ("Payload value must not be empty").
        String message = bindingResult == null || !bindingResult.hasErrors()
                ? "Invalid request!"
                : bindingResult.getAllErrors()
                        .stream()
                        .map(error -> error instanceof FieldError fieldError
                                ? fieldError.getField() + " " + fieldError.getDefaultMessage()
                                : error.getDefaultMessage())
                        .collect(Collectors.joining(", "));

        return new GameSessionErrorResponse(
                GameErrorCode.INVALID_REQUEST,
                message
        );
    }

    // A body that isn't JSON, or has a field of the wrong type, such as
    // {"clueId": "abc"} or a wager too large for an int. Jackson's message
    // names internal classes, so it isn't passed on.
    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleUnreadableRequest(
            MessageConversionException exception
    ) {
        return new GameSessionErrorResponse(
                GameErrorCode.INVALID_REQUEST,
                "Invalid request: the body isn't valid JSON for this action"
        );
    }

    // Anything else is a server bug. Without this the sender would get no
    // reply at all; the more specific handlers above still win.
    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleUnexpected(
            Exception exception
    ) {
        log.error("A STOMP action failed", exception);

        return new GameSessionErrorResponse(
                GameErrorCode.INTERNAL_ERROR,
                "Something went wrong on the server. Please try again."
        );
    }

}
