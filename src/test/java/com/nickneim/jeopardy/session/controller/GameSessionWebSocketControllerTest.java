package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.GameSessionErrorResponse;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.service.GameSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.support.AnnotationExceptionHandlerMethodResolver;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Which @MessageExceptionHandler Spring picks for an exception, resolved the
 * way Spring resolves it. An unexpected exception is hard to cause in the
 * WebSocket integration test, so the catch-all is checked here.
 */
class GameSessionWebSocketControllerTest {

    private final AnnotationExceptionHandlerMethodResolver resolver =
            new AnnotationExceptionHandlerMethodResolver(GameSessionWebSocketController.class);

    private final GameSessionWebSocketController controller =
            new GameSessionWebSocketController(mock(GameSessionService.class));

    @Test
    void anUnexpectedException_getsTheCatchAllHandler() throws Exception {
        Exception exception = new IllegalStateException("internal detail");

        Method handler = resolver.resolveMethod(exception);
        GameSessionErrorResponse error = (GameSessionErrorResponse) handler.invoke(controller, exception);

        assertEquals("handleUnexpected", handler.getName());
        assertEquals(GameErrorCode.INTERNAL_ERROR, error.code());
        // The exception's own message could hold internal details.
        assertEquals("Something went wrong on the server. Please try again.", error.message());
    }

    // Spring only logs what an exception handler throws, so rethrowing would
    // leave the sender without a reply.
    @Test
    void aConversionFailureForAnotherType_isAnInvalidRequest() throws Exception {
        Exception exception = new ConversionFailedException(
                TypeDescriptor.valueOf(String.class),
                TypeDescriptor.valueOf(Long.class),
                "abc",
                new NumberFormatException("abc")
        );

        Method handler = resolver.resolveMethod(exception);
        GameSessionErrorResponse error = (GameSessionErrorResponse) handler.invoke(controller, exception);

        assertEquals(GameErrorCode.INVALID_REQUEST, error.code());
        assertEquals("Invalid request: a value in the destination has the wrong format", error.message());
    }

    @Test
    void theSpecificHandlers_stillWinOverTheCatchAll() {
        assertEquals(
                "handleInvalidGameAction",
                resolver.resolveMethod(new InvalidGameActionException(GameErrorCode.INVALID_STATE, "no")).getName()
        );
        assertEquals(
                "handleUnreadableRequest",
                resolver.resolveMethod(new MessageConversionException("bad JSON")).getName()
        );
    }
}
