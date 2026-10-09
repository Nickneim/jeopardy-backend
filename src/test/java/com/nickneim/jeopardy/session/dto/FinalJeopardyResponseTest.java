package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FinalJeopardyResponseTest {

    private static final String FINAL_QUESTION = "This war began in 1775.";
    private static final String FINAL_ANSWER = "The American Revolution";
    private static final String WRONG_ANSWER = "The War of 1812";
    private static final String FINAL_COMMENTS = "(Alex: Wars the U.S. fought in.)";

    // The JSON checks look for "wager":777 rather than the bare number, which
    // could also appear inside the random session id.
    private static final int SECRET_WAGER = 777;

    private final BoardClue jeopardyClue =
            new BoardClue(101L, 1, "First president?", "George Washington", 1000, false);
    private final BoardClue finalClue =
            new BoardClue(301L, 1, FINAL_QUESTION, FINAL_ANSWER, 2000, false);

    private GameSession session;
    private GameSessionPlayer player1;
    private GameSessionPlayer player2;

    @BeforeEach
    void setUp() {
        session = new GameSession(UUID.randomUUID(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(jeopardyClue))
                )),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("AMERICAN WARS", FINAL_COMMENTS, List.of(finalClue))
                ))
        )));

        player1 = new GameSessionPlayer(1L, "Nico");
        player2 = new GameSessionPlayer(2L, "John");
        session.addPlayer(player1);
        session.addPlayer(player2);

        session.startGame(player1.getId());
    }

    @Test
    void finalJeopardy_isNullBeforeFinalJeopardy() {
        assertNull(GameSessionResponse.from(session).finalJeopardy());
    }

    @Test
    void finalJeopardy_isNullWhenAGameWithoutFinalJeopardyFinishes() {
        GameSession noFinal = new GameSession(UUID.randomUUID(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(jeopardyClue))
                ))
        )));
        noFinal.addPlayer(player1);
        noFinal.startGame(player1.getId());
        noFinal.chooseClue(player1.getId(), jeopardyClue.id());
        noFinal.finishRevealingClue();
        noFinal.timeoutBuzzer();
        noFinal.finishRevealingAnswer();

        assertEquals(GameSessionState.FINISHED_GAME, noFinal.getState());
        assertNull(GameSessionResponse.from(noFinal).finalJeopardy());
    }

    @Test
    void wagering_showsCategoryAndWhoHasWageredOnly() {
        reachFinalJeopardy();
        session.placeFinalJeopardyWager(player1.getId(), SECRET_WAGER);

        GameSessionResponse response = GameSessionResponse.from(session);

        assertNull(response.currentClue());
        assertEquals(
                new FinalJeopardyResponse(
                        "AMERICAN WARS",
                        FINAL_COMMENTS,
                        null,
                        null,
                        List.of(
                                new FinalJeopardyPlayerResponse(1L, true, false, null, null, null),
                                new FinalJeopardyPlayerResponse(2L, false, false, null, null, null)
                        )
                ),
                response.finalJeopardy()
        );

        String json = toJson(response);
        assertTrue(json.contains("AMERICAN WARS"), json);
        assertTrue(json.contains("\"categoryComments\":\"" + FINAL_COMMENTS + "\""), json);
        assertFalse(json.contains("\"wager\":" + SECRET_WAGER), "leaked a wager: " + json);
        assertFalse(json.contains(FINAL_QUESTION), "leaked the question: " + json);
        assertFalse(json.contains(FINAL_ANSWER), "leaked the answer: " + json);
    }

    @Test
    void answering_showsClueButKeepsWagersAndAnswersSecret() {
        reachFinalJeopardy();
        session.placeFinalJeopardyWager(player1.getId(), SECRET_WAGER);
        session.timeoutFinalJeopardyWagering();
        session.answerFinalJeopardy(player2.getId(), WRONG_ANSWER);

        GameSessionResponse response = GameSessionResponse.from(session);

        assertEquals(
                new FinalJeopardyResponse(
                        "AMERICAN WARS",
                        FINAL_COMMENTS,
                        FINAL_QUESTION,
                        null,
                        List.of(
                                new FinalJeopardyPlayerResponse(1L, true, false, null, null, null),
                                new FinalJeopardyPlayerResponse(2L, true, true, null, null, null)
                        )
                ),
                response.finalJeopardy()
        );

        String json = toJson(response);
        assertTrue(json.contains(FINAL_QUESTION), json);
        assertFalse(json.contains("\"wager\":" + SECRET_WAGER), "leaked a wager: " + json);
        assertFalse(json.contains(WRONG_ANSWER), "leaked a player's answer: " + json);
        assertFalse(json.contains(FINAL_ANSWER), "leaked the correct response: " + json);
    }

    @Test
    void revealing_showsEverythingButHasNotChangedScoresYet() {
        playFinalJeopardyToReveal();

        GameSessionResponse response = GameSessionResponse.from(session);

        // Pins the JSON shape the leak checks above rely on.
        assertTrue(toJson(response).contains("\"wager\":" + SECRET_WAGER));

        assertEquals(
                new FinalJeopardyResponse(
                        "AMERICAN WARS",
                        FINAL_COMMENTS,
                        FINAL_QUESTION,
                        FINAL_ANSWER,
                        List.of(
                                new FinalJeopardyPlayerResponse(1L, true, true, SECRET_WAGER, FINAL_ANSWER, true),
                                new FinalJeopardyPlayerResponse(2L, true, true, 0, WRONG_ANSWER, false)
                        )
                ),
                response.finalJeopardy()
        );
        assertEquals(1000, response.players().get(0).score());
    }

    @Test
    void finishedGame_keepsShowingResultsWithUpdatedScores() {
        playFinalJeopardyToReveal();
        session.finishFinalJeopardyReveal();

        GameSessionResponse response = GameSessionResponse.from(session);

        assertEquals(GameSessionState.FINISHED_GAME, response.state());
        assertEquals(FINAL_ANSWER, response.finalJeopardy().answer());
        assertEquals(1000 + SECRET_WAGER, response.players().get(0).score());
        assertEquals(0, response.players().get(1).score());
    }

    // Player 1 answers the only Jeopardy clue correctly (1000 points), so
    // they can wager; player 2 stays on 0.
    private void reachFinalJeopardy() {
        session.chooseClue(player1.getId(), jeopardyClue.id());
        session.finishRevealingClue();
        session.buzz(player1.getId());
        session.answerClue(player1.getId(), "George Washington");
        session.finishRevealingAnswer();

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
    }

    private void playFinalJeopardyToReveal() {
        reachFinalJeopardy();
        session.placeFinalJeopardyWager(player1.getId(), SECRET_WAGER);
        session.placeFinalJeopardyWager(player2.getId(), 0);
        session.timeoutFinalJeopardyWagering();
        session.answerFinalJeopardy(player1.getId(), FINAL_ANSWER);
        session.answerFinalJeopardy(player2.getId(), WRONG_ANSWER);
        session.timeoutFinalJeopardyAnswering();
    }

    private String toJson(GameSessionResponse response) {
        return JsonMapper.builder().build().writeValueAsString(response);
    }
}
