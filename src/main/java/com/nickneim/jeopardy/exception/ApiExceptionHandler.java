package com.nickneim.jeopardy.exception;

import com.nickneim.jeopardy.dto.ApiErrorResponse;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
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
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * Every handler returns its status through error(...), which also puts it in
 * the body, so the status line and ApiErrorResponse.status can't disagree.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleGameNotFound(GameNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, ApiErrorCode.GAME_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ClueNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleClueNotFound(ClueNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, ApiErrorCode.CLUE_NOT_FOUND, exception.getMessage());
    }

    /**
     * A game rule broken by a REST call. The only one today is a missing
     * session (GameSessionNotFoundException, 404); the rest are STOMP
     * errors, mapped here so that one reaching REST is a 4xx, not a 500.
     * The body keeps the GameErrorCode, since several share a status.
     */
    @ExceptionHandler(InvalidGameActionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidGameAction(
            InvalidGameActionException exception
    ) {
        HttpStatus status = switch (exception.getCode()) {
            case SESSION_NOT_FOUND, PLAYER_NOT_FOUND, CLUE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_STATE, TOO_LATE, CLUE_ALREADY_PLAYED -> HttpStatus.CONFLICT;
            case NOT_YOUR_TURN, NOT_HOST, PLAYER_NOT_IN_SESSION -> HttpStatus.FORBIDDEN;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case INVALID_REQUEST, INVALID_WAGER -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return error(status, exception.getCode().name(), exception.getMessage());
    }

    // 422 rather than 409: the request is well formed and the game exists, but
    // its content can't be used to create a session. Nothing about the
    // server's state conflicts with it, and retrying won't help.
    @ExceptionHandler(UnplayableGameException.class)
    public ResponseEntity<ApiErrorResponse> handleUnplayableGame(UnplayableGameException exception) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.UNPLAYABLE_GAME, exception.getMessage());
    }

    // 409: the request is fine, but the session doesn't take new players
    // right now (GameSession.isJoinable).
    @ExceptionHandler(SessionNotJoinableException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionNotJoinable(SessionNotJoinableException exception) {
        return error(HttpStatus.CONFLICT, ApiErrorCode.SESSION_NOT_JOINABLE, exception.getMessage());
    }

    // 409: the code a create asked for is already in use; the client
    // retries with another.
    @ExceptionHandler(GameSessionAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionAlreadyExists(
            GameSessionAlreadyExistsException exception
    ) {
        return error(HttpStatus.CONFLICT, ApiErrorCode.SESSION_ALREADY_EXISTS, exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        return invalidRequest("Invalid request parameters");
    }

    /**
     * A request body that fails bean validation, such as a blank playerName.
     * The message names every invalid field, e.g. "playerName must not be blank".
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequestBody(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining(", "));

        return invalidRequest(message.isEmpty() ? "Invalid request body" : message);
    }

    /**
     * A request body that isn't valid JSON, is missing, or has a field of the
     * wrong type, such as {"gameId": "abc"}. Jackson's message names internal
     * classes, so it isn't passed on.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequestBody(
            HttpMessageNotReadableException exception
    ) {
        return invalidRequest("Invalid request: the body isn't valid JSON for this request");
    }

    // An unknown sort, direction or stage, or a 'from' after 'to'.
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(InvalidParameterException exception) {
        return invalidRequest(exception.getMessage());
    }

    /**
     * Only date, boolean and int (page and size) parameters and session codes
     * get an ApiErrorResponse; a non-numeric page or size gets the same
     * message as an out-of-range one. Rethrowing any other mismatch (such as
     * a non-numeric id) leaves it to Spring's default 400 handling.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidFilterValue(
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

        return invalidRequest(message);
    }

    private static ResponseEntity<ApiErrorResponse> invalidRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, message);
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, ApiErrorCode code, String message) {
        return error(status, code.name(), message);
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(status.value(), code, message));
    }

}
