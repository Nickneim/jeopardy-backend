package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.*;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.service.GameSessionService;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class GameSessionWebSocketController {

    private final GameSessionService gameSessionService;

    public GameSessionWebSocketController(
            GameSessionService gameSessionService
    ) {
        this.gameSessionService = gameSessionService;
    }

    @MessageMapping("/sessions/{sessionId}/join")
    public void joinSession(
            @DestinationVariable UUID sessionId,
            @Valid JoinSessionRequest request
    ) {
        gameSessionService.joinSession(
                sessionId,
                request.playerName()
        );
    }

    @MessageMapping("/sessions/{sessionId}/start")
    public void startGame(
            @DestinationVariable UUID sessionId
    ) {
        gameSessionService.startGame(sessionId);
    }

    @MessageMapping("/sessions/{sessionId}/choose-clue")
    public void chooseClue(
            @DestinationVariable UUID sessionId,
            @Valid ChooseClueRequest request
    ) {
        gameSessionService.chooseClue(
                sessionId,
                request.playerId(),
                request.clueId()
        );
    }

    @MessageMapping("/sessions/{sessionId}/buzz")
    public void buzz(
            @DestinationVariable UUID sessionId,
            @Valid BuzzRequest request
    ) {
        gameSessionService.buzz(
                sessionId,
                request.playerId()
        );
    }

    @MessageMapping("/sessions/{sessionId}/answer")
    public void answerClue(
            @DestinationVariable UUID sessionId,
            @Valid AnswerClueRequest request
    ) {
        gameSessionService.answerClue(
                sessionId,
                request.playerId(),
                request.answer()
        );
    }


    @MessageMapping("/sessions/{sessionId}/place-daily-double-wager")
    public void placeDailyDoubleWager(
            @DestinationVariable UUID sessionId,
            @Valid PlaceDailyDoubleWagerRequest request
    ) {
        gameSessionService.placeDailyDoubleWager(
                sessionId,
                request.playerId(),
                request.wager()
        );
    }

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

    @MessageExceptionHandler(GameSessionNotFoundException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public GameSessionErrorResponse handleSessionNotFound(
            GameSessionNotFoundException exception
    ) {

        return new GameSessionErrorResponse(
                exception.getCode().name(),
                exception.getMessage()
        );
    }

}
