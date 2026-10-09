package com.nickneim.jeopardy.exception;

import com.nickneim.jeopardy.game.dto.ApiErrorResponse;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.exception.InvalidGameFilterException;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.LateJoinNotAllowedException;
import com.nickneim.jeopardy.session.exception.UnplayableGameException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GameNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleGameNotFound(GameNotFoundException exception) {
        return new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(ClueNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleClueNotFound(ClueNotFoundException exception) {
        return new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(GameSessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleGameSessionNotFound(
            GameSessionNotFoundException exception
    ) {
        return new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage()
        );
    }

    // 422 rather than 409: the request is well formed and the game exists, but
    // its content can't be used to create a session. Nothing about the
    // server's state conflicts with it, and retrying won't help.
    @ExceptionHandler(UnplayableGameException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiErrorResponse handleUnplayableGame(UnplayableGameException exception) {
        return new ApiErrorResponse(
                HttpStatus.UNPROCESSABLE_CONTENT.value(),
                exception.getMessage()
        );
    }

    // 409: the request is fine, but the session's state (started, with late
    // joining blocked by the host) doesn't allow it.
    @ExceptionHandler(LateJoinNotAllowedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleLateJoinNotAllowed(LateJoinNotAllowedException exception) {
        return new ApiErrorResponse(
                HttpStatus.CONFLICT.value(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        return new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid request parameters"
        );
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

        return new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message.isEmpty() ? "Invalid request body" : message
        );
    }

    @ExceptionHandler(InvalidGameFilterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidGameFilter(InvalidGameFilterException exception) {
        return new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage()
        );
    }

    /**
     * Only date and boolean filter parameters get an ApiErrorResponse.
     * Rethrowing any other mismatch (such as a non-numeric page or id) leaves
     * it to Spring's default 400 handling, as before these filters existed.
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
        } else {
            throw exception;
        }

        return new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), message);
    }


}