package com.nickneim.jeopardy.exception;

import com.nickneim.jeopardy.dto.ApiErrorResponse;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.exception.InvalidGameFilterException;
import com.nickneim.jeopardy.session.exception.GameSessionAlreadyExistsException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.exception.SessionNotJoinableException;
import com.nickneim.jeopardy.session.exception.UnplayableGameException;
import com.nickneim.jeopardy.session.model.SessionCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({
            GameNotFoundException.class,
            ClueNotFoundException.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A game rule broken by a REST call. The only one today is a missing
     * session (GameSessionNotFoundException, 404); the rest are STOMP
     * errors, mapped here so that one reaching REST is a 4xx, not a 500.
     */
    @ExceptionHandler(InvalidGameActionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidGameAction(
            InvalidGameActionException exception
    ) {
        HttpStatus status = switch (exception.getCode()) {
            case SESSION_NOT_FOUND, PLAYER_NOT_FOUND, CLUE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_STATE, CLUE_ALREADY_PLAYED -> HttpStatus.CONFLICT;
            case NOT_YOUR_TURN, NOT_HOST, PLAYER_NOT_IN_SESSION -> HttpStatus.FORBIDDEN;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case INVALID_REQUEST, INVALID_WAGER -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(error(status, exception.getMessage()));
    }

    // 422 rather than 409: the request is well formed and the game exists, but
    // its content can't be used to create a session. Nothing about the
    // server's state conflicts with it, and retrying won't help.
    @ExceptionHandler(UnplayableGameException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiErrorResponse handleUnplayableGame(UnplayableGameException exception) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    }

    // 409: the request is fine, but the session's state doesn't allow it:
    // it doesn't take new players right now (GameSession.isJoinable), or
    // (for a create) its id is already in use.
    @ExceptionHandler({
            SessionNotJoinableException.class,
            GameSessionAlreadyExistsException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleConflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request parameters");
    }

    /**
     * A request body that fails bean validation, such as a blank playerName.
     * The message names every invalid field, e.g. "playerName must not be blank".
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidRequestBody(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining(", "));

        return error(HttpStatus.BAD_REQUEST, message.isEmpty() ? "Invalid request body" : message);
    }

    /**
     * A request body that isn't valid JSON, is missing, or has a field of the
     * wrong type, such as {"gameId": "abc"}. Jackson's message names internal
     * classes, so it isn't passed on.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleUnreadableRequestBody(
            HttpMessageNotReadableException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request: the body isn't valid JSON for this request");
    }

    @ExceptionHandler(InvalidGameFilterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidGameFilter(InvalidGameFilterException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * Only date, boolean and int (page and size) parameters and session codes
     * get an ApiErrorResponse; a non-numeric page or size gets the same
     * message as an out-of-range one. Rethrowing any other mismatch (such as
     * a non-numeric id) leaves it to Spring's default 400 handling.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidFilterValue(
            MethodArgumentTypeMismatchException exception
    ) throws MethodArgumentTypeMismatchException {
        String parameter = "'" + exception.getName() + "'";
        String message;

        if (exception.getRequiredType() == LocalDate.class) {
            message = "Invalid date for " + parameter + ": expected YYYY-MM-DD";
        } else if (exception.getRequiredType() == Boolean.class) {
            message = "Invalid value for " + parameter + ": expected true or false";
        } else if (exception.getRequiredType() == int.class) {
            message = "Invalid request parameters";
        } else if (exception.getRequiredType() == SessionCode.class) {
            message = "Invalid session code: expected " + SessionCode.LENGTH + " letters and digits";
        } else {
            throw exception;
        }

        return error(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiErrorResponse error(HttpStatus status, String message) {
        return new ApiErrorResponse(status.value(), message);
    }

}