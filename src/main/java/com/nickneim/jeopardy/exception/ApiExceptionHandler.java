package com.nickneim.jeopardy.exception;

import com.nickneim.jeopardy.game.dto.ApiErrorResponse;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.exception.InvalidGameFilterException;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

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

    @ExceptionHandler(InvalidGameFilterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidGameFilter(InvalidGameFilterException exception) {
        return new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage()
        );
    }

    /**
     * Only date parameters get an ApiErrorResponse. Rethrowing any other
     * mismatch (such as a non-numeric page or id) leaves it to Spring's default
     * 400 handling, as before date parameters existed.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidDate(
            MethodArgumentTypeMismatchException exception
    ) throws MethodArgumentTypeMismatchException {
        if (exception.getRequiredType() != LocalDate.class) {
            throw exception;
        }

        return new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid date for '" + exception.getName() + "': expected YYYY-MM-DD"
        );
    }


}