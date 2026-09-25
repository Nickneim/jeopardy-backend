package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.*;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionTest {


    private GameSession session;

    private GameSessionPlayer player1;
    private GameSessionPlayer player2;

    private Clue jeopardyClue1;
    private Clue jeopardyClue2;
    private Clue doubleJeopardyClue1;
    private Clue finalJeopardyClue;


    @Test
    void addPlayer_addsPlayerToSession() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );
        UUID sessionId = UUID.randomUUID();
        GameSession session =
                new GameSession(sessionId, game);

        GameSessionPlayer player =
                new GameSessionPlayer(1L, "Nico");

        session.addPlayer(player);

        assertEquals(
                1,
                session.getPlayers().size()
        );

        assertEquals(
                player,
                session.getPlayers().getFirst()
        );
    }

    @Test
    void newPlayer_startsWithZeroScore() {
        GameSessionPlayer player =
                new GameSessionPlayer(1L, "Nico");

        assertEquals(0, player.getScore());
    }

    @Test
    void addScore_changesPlayerScore() {
        GameSessionPlayer player =
                new GameSessionPlayer(1L, "Nico");

        player.addScore(500);

        assertEquals(500, player.getScore());
    }

    @BeforeEach
    void setUp() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category history = new Category(
                "HISTORY",
                (short) 1,
                null,
                game
        );

        Category science = new Category(
                "SCIENCE",
                (short) 2,
                null,
                game
        );

        Category doubleHistory = new Category(
                "HISTORY",
                (short) 7,
                null,
                game
        );

        Category finalCategory = new Category(
                "FINAL HISTORY",
                (short) 13,
                null,
                game
        );

        jeopardyClue1 = new Clue(
                "Who was the first president of the United States?",
                "George Washington",
                (short) 1,
                ClueType.TEXT,
                null,
                history
        );

        jeopardyClue2 = new Clue(
                "This country has the city of Paris as its capital.",
                "France",
                (short) 2,
                ClueType.TEXT,
                null,
                science
        );

        doubleJeopardyClue1 = new Clue(
                "This planet is known as the Red Planet.",
                "Mars",
                (short) 1,
                ClueType.TEXT,
                null,
                doubleHistory
        );

        finalJeopardyClue = new Clue(
                "This historical event began in 1775.",
                "The American Revolution",
                (short) 1,
                ClueType.TEXT,
                null,
                finalCategory
        );

        history.getClues().add(jeopardyClue1);
        science.getClues().add(jeopardyClue2);
        doubleHistory.getClues().add(doubleJeopardyClue1);
        finalCategory.getClues().add(finalJeopardyClue);

        game.getCategories().add(history);
        game.getCategories().add(science);
        game.getCategories().add(doubleHistory);
        game.getCategories().add(finalCategory);

        // Generated IDs don't exist when entities are constructed directly.
        ReflectionTestUtils.setField(jeopardyClue1, "id", 101L);
        ReflectionTestUtils.setField(jeopardyClue2, "id", 102L);
        ReflectionTestUtils.setField(doubleJeopardyClue1, "id", 201L);
        ReflectionTestUtils.setField(finalJeopardyClue, "id", 301L);

        session = new GameSession(
                UUID.randomUUID(),
                game
        );

        player1 = new GameSessionPlayer(1L, "Nico");
        player2 = new GameSessionPlayer(2L, "John");

        session.addPlayer(player1);
        session.addPlayer(player2);
    }

    @Test
    void newSession_startsWaitingForStart() {
        assertEquals(
                GameSessionState.WAITING_FOR_START,
                session.getState()
        );
    }

    @Test
    void startGame_startsJeopardyRound() {
        session.startGame();

        assertEquals(
                GameRound.JEOPARDY,
                session.getRound()
        );
        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );
        assertEquals(
                player1.getId(),
                session.getPlayerChoosingClue()
        );
    }

    @Test
    void startGame_cannotBeCalledAfterGameHasStarted() {
        session.startGame();

        assertThrows(
                InvalidGameActionException.class,
                session::startGame
        );
    }

    @Test
    void chooseClue_selectsAvailableClue() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );

        assertEquals(
                jeopardyClue1.getId(),
                session.getChosenClueId()
        );
        assertEquals(
                GameSessionState.REVEALING_CLUE,
                session.getState()
        );
    }

    @Test
    void chooseClue_rejectsWhenNotChoosingClue() {
        assertThrows(
                InvalidGameActionException.class,
                () -> session.chooseClue(
                        player1.getId(),
                        jeopardyClue1.getId()
                )
        );
    }

    @Test
    void chooseClue_rejectsPlayerWhoIsNotChoosing() {
        session.startGame();

        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.chooseClue(
                                player2.getId(),
                                jeopardyClue1.getId()
                        )
                );

        assertEquals(
                GameErrorCode.NOT_YOUR_TURN,
                exception.getCode()
        );
    }

    @Test
    void chooseClue_rejectsAlreadySelectedClue() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );

        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        // player 1 should still be the chooser after timeout
        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.chooseClue(
                                player1.getId(),
                                jeopardyClue1.getId()
                        )
                );

        assertEquals(
                GameErrorCode.CLUE_ALREADY_PLAYED,
                exception.getCode()
        );

    }

    @Test
    void chooseClue_rejectsUnknownClue() {
        session.startGame();

        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.chooseClue(
                                player1.getId(),
                                999L
                        )
                );

        assertEquals(
                GameErrorCode.CLUE_NOT_FOUND,
                exception.getCode()
        );
    }

    @Test
    void chooseClue_rejectsClueFromDifferentRound() {
        session.startGame();

        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.chooseClue(
                                player1.getId(),
                                doubleJeopardyClue1.getId()
                        )
                );

        assertEquals(
                GameErrorCode.CLUE_NOT_FOUND,
                exception.getCode()
        );
    }

    @Test
    void finishRevealingClue_startsBuzzerPhase() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );

        session.finishRevealingClue();

        assertEquals(
                GameSessionState.WAITING_FOR_BUZZER,
                session.getState()
        );
    }

    @Test
    void buzz_movesPlayerToAnswerPhase() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();

        session.buzz(player2.getId());

        assertEquals(
                player2.getId(),
                session.getPlayerWhoBuzzed()
        );
        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                session.getState()
        );
    }

    @Test
    void buzz_rejectsWhenNotWaitingForBuzzer() {
        session.startGame();

        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.buzz(player2.getId())
                );

        assertEquals(
                GameErrorCode.INVALID_STATE,
                exception.getCode()
        );
    }

    @Test
    void buzz_rejectsPlayerNotInSession() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();

        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.buzz(999L)
                );

        assertEquals(
                GameErrorCode.PLAYER_NOT_IN_SESSION,
                exception.getCode()
        );
    }

    @Test
    void buzz_rejectsPlayerWhoAlreadyBuzzed() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();

        session.buzz(player2.getId());

        assertTrue(
                session.getPlayersAlreadyBuzzed()
                        .contains(player2.getId())
        );

        session.timeoutAnswer();

        InvalidGameActionException exception =
                assertThrows(
                    InvalidGameActionException.class,
                    () -> session.buzz(player2.getId())
                );


        assertEquals(
                GameErrorCode.NOT_YOUR_TURN,
                exception.getCode()
        );
    }

    @Test
    void timeoutBuzzer_revealsAnswer() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();

        session.timeoutBuzzer();

        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                session.getState()
        );
    }

    @Test
    void answerClue_correctAnswer_awardsPoints() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.answerClue(
                player2.getId(),
                "George Washington"
        );

        assertEquals(
                100,
                player2.getScore()
        );
        assertEquals(
                GameSessionState.REVEALING_ANSWER,
                session.getState()
        );
        assertEquals(
                player2.getId(),
                session.getPlayerChoosingClue()
        );
    }

    @Test
    void answerClue_wrongAnswer_deductsPoints() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.answerClue(
                player2.getId(),
                "Abraham Lincoln"
        );

        assertEquals(
                -100,
                player2.getScore()
        );
    }

    @Test
    void answerClue_rejectsPlayerWhoDidNotBuzz() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.buzz(player2.getId());

        InvalidGameActionException exception =
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.answerClue(
                                player1.getId(),
                                "George Washington"
                        )
                );

        assertEquals(
                GameErrorCode.NOT_YOUR_TURN,
                exception.getCode()
        );
    }

    @Test
    void timeoutAnswer_deductsPoints() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.timeoutAnswer();

        assertEquals(
                -100,
                player2.getScore()
        );
    }

    @Test
    void timeoutAnswer_whenAllPlayersBuzzed_revealsAnswer() {
        session.startGame();

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();

        session.buzz(player1.getId());
        session.timeoutAnswer();

        session.buzz(player2.getId());
        session.timeoutAnswer();

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void finishRevealingAnswer_startsNextRoundWhenJeopardyIsFinished() {
        session.startGame();

        // Select every available Jeopardy clue.
        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(
                player1.getId(),
                jeopardyClue2.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(
                GameRound.DOUBLE_JEOPARDY,
                session.getRound()
        );
    }

    @Test
    void setUpNewRound_makesDoubleJeopardyCluesAvailable() {
        session.startGame();

        // Finish Jeopardy round.
        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(
                player1.getId(),
                jeopardyClue2.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(
                GameRound.DOUBLE_JEOPARDY,
                session.getRound()
        );

        session.chooseClue(
                player1.getId(),
                doubleJeopardyClue1.getId()
        );

        assertEquals(
                doubleJeopardyClue1.getId(),
                session.getChosenClueId()
        );
    }


    @Test
    void finishRevealingAnswer_startsFinalRoundWhenDoubleJeopardyIsFinished() {
        session.startGame();

        // Select every available Jeopardy clue.
        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(
                player1.getId(),
                jeopardyClue2.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(
                player1.getId(),
                doubleJeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(
                GameRound.FINAL_JEOPARDY,
                session.getRound()
        );
    }


    @Test
    void finishRevealingAnswer_finishesGameWhenFinalJeopardyIsFinished() {
        session.startGame();

        // Select every available Jeopardy clue.
        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(
                player1.getId(),
                jeopardyClue2.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(
                player1.getId(),
                doubleJeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        // This test needs to be updated when Final Jeopardy! works as it should (with betting)
        session.chooseClue(
                player1.getId(),
                finalJeopardyClue.getId()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(
                GameSessionState.FINISHED_GAME,
                session.getState()
        );
    }

}
