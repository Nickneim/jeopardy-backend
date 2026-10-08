package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.*;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionTest {


    private GameSession session;

    private GameSessionPlayer player1;
    private GameSessionPlayer player2;

    private Clue jeopardyClue1;
    private Clue jeopardyClue2;
    private Clue dailyDoubleClue1;
    private Clue doubleJeopardyClue1;
    private Clue doubleJeopardyDailyDouble;
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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

        assertThrows(
                InvalidGameActionException.class,
                () -> session.startGame(player1.getId())
        );
    }

    @Test
    void chooseClue_selectsAvailableClue() {
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

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
                200,
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
        session.startGame(player1.getId());

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
                -200,
                player2.getScore()
        );
    }

    @Test
    void answerClue_rejectsPlayerWhoDidNotBuzz() {
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                jeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.timeoutAnswer();

        assertEquals(
                -200,
                player2.getScore()
        );
    }

    @Test
    void timeoutAnswer_whenAllPlayersBuzzed_revealsAnswer() {
        session.startGame(player1.getId());

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
        session.startGame(player1.getId());

        // Select every available Jeopardy clue.
        finishJeopardyRound();

        assertEquals(
                GameRound.DOUBLE_JEOPARDY,
                session.getRound()
        );

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );

        assertEquals(
                Set.of(doubleJeopardyClue1.getId()),
                session.getAvailableClues()
        );

        assertTrue(
                session.getCluesAlreadySelected().isEmpty()
        );

    }

    @Test
    void setUpNewRound_makesDoubleJeopardyCluesAvailable() {
        session.startGame(player1.getId());

        // Finish Jeopardy round.
        finishJeopardyRound();

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
        session.startGame(player1.getId());

        // Select every available Jeopardy clue.
        finishJeopardyRound();

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

        assertEquals(
                GameSessionState.CHOOSING_CLUE,
                session.getState()
        );

        assertEquals(
                Set.of(finalJeopardyClue.getId()),
                session.getAvailableClues()
        );
    }


    @Test
    void finishRevealingAnswer_finishesGameWhenFinalJeopardyIsFinished() {
        session.startGame(player1.getId());

        // Select every available Jeopardy clue.
        finishJeopardyRound();

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

    @Test
    void answerClue_correctAnswerInDoubleJeopardy_awardsDoubleValue() {
        session.startGame(player1.getId());

        // Finish Jeopardy.
        finishJeopardyRound();

        // Start Double Jeopardy clue.
        session.chooseClue(
                player1.getId(),
                doubleJeopardyClue1.getId()
        );
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.answerClue(
                player2.getId(),
                "Mars"
        );

        assertEquals(
                400,
                player2.getScore()
        );
    }


    private void addDailyDoubleClue() {
        Category history = session.getGame().getCategories().getFirst();

        dailyDoubleClue1 = new Clue(
                "Who was the second president of the United States?",
                "John Adams",
                (short) 2,
                ClueType.TEXT,
                null,
                history
        );
        ReflectionTestUtils.setField(dailyDoubleClue1, "id", 103L);

        history.getClues().add(dailyDoubleClue1);

        GameDailyDouble dailyDouble = new GameDailyDouble(session.getGame(), dailyDoubleClue1);

        session.getGame().getDailyDoubles().add(dailyDouble);
    }


    @Test
    void chooseClue_forDailyDouble_waitsForWagerBeforeRevealing() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        assertEquals(
                GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER,
                session.getState()
        );
    }

    @Test
    void placeDailyDoubleWager_rejectsPlayerWhoDidNotChooseClue() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeDailyDoubleWager(
                        player2.getId(),
                        500
                )
        );

        assertEquals(GameErrorCode.NOT_YOUR_TURN, exception.getCode());
    }

    @Test
    void placeDailyDoubleWager_rejectsWagerWithValueTooHigh() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeDailyDoubleWager(
                        player1.getId(),
                        3000
                )
        );

        assertEquals(GameErrorCode.INVALID_WAGER, exception.getCode());
    }

    @Test
    void placeDailyDoubleWager_rejectsWagerWithValueTooLow() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeDailyDoubleWager(
                        player1.getId(),
                        4
                )
        );

        assertEquals(GameErrorCode.INVALID_WAGER, exception.getCode());
    }

    @Test
    void placeDailyDoubleWager_acceptsValidWager() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        session.placeDailyDoubleWager(player1.getId(), 500);

        assertEquals(500, session.getPlayers().getFirst().getWager());
        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
    }

    @Test
    void finishRevealingClue_forDailyDouble_waitsForAnswer() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        assertEquals(
                GameSessionState.WAITING_FOR_ANSWER,
                session.getState()
        );
    }

    @Test
    void answerClue_correctDailyDouble_addsWagerToScore() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );

        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        session.answerClue(player1.getId(), "John Adams");

        assertEquals(500, session.getPlayers().getFirst().getScore());
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void answerClue_incorrectDailyDouble_deductsWager() {
        addDailyDoubleClue();

        session.startGame(player1.getId());
        Long playerId = session.getPlayers().getFirst().getId();

        session.chooseClue(
                playerId,
                dailyDoubleClue1.getId()
        );

        session.placeDailyDoubleWager(playerId, 500);
        session.finishRevealingClue();

        session.answerClue(playerId, "George Washington");

        assertEquals(-500, player1.getScore());
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void timeoutWaitingForDailyDouble_revealsClue() {
        addDailyDoubleClue();
        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.getId()
        );
        session.timeoutDailyDoubleWager();

        assertEquals(
                GameSessionState.REVEALING_CLUE,
                session.getState()
        );

        assertEquals(5, player1.getWager());
    }

    // -------------------------------------------------------------------------
    // Daily Double: wager validation
    // -------------------------------------------------------------------------

    @Test
    void placeDailyDoubleWager_rejectsWhenNotWaitingForWager() {
        addDailyDoubleClue();
        session.startGame(player1.getId());

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeDailyDoubleWager(player1.getId(), 500)
        );

        assertEquals(GameErrorCode.INVALID_STATE, exception.getCode());
    }

    @Test
    void placeDailyDoubleWager_acceptsMinimumWager() {
        startDailyDouble();

        session.placeDailyDoubleWager(player1.getId(), 5);

        assertEquals(5, player1.getWager());
        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
    }

    @Test
    void placeDailyDoubleWager_inJeopardy_allowsUpTo1000() {
        startDailyDouble();

        assertInvalidWager(1001);

        session.placeDailyDoubleWager(player1.getId(), 1000);

        assertEquals(1000, player1.getWager());
    }

    @Test
    void placeDailyDoubleWager_allowsWageringFullScoreWhenAboveRoundMax() {
        startDailyDouble();
        player1.addScore(1500);

        assertInvalidWager(1501);

        session.placeDailyDoubleWager(player1.getId(), 1500);

        assertEquals(1500, player1.getWager());
    }

    @Test
    void placeDailyDoubleWager_withNegativeScore_allowsUpToRoundMax() {
        startDailyDouble();
        player1.addScore(-500);

        assertInvalidWager(1001);

        session.placeDailyDoubleWager(player1.getId(), 1000);

        assertEquals(1000, player1.getWager());
    }

    @Test
    void placeDailyDoubleWager_inDoubleJeopardy_allowsUpTo2000() {
        addDoubleJeopardyDailyDouble();
        session.startGame(player1.getId());
        finishJeopardyRound();

        session.chooseClue(
                player1.getId(),
                doubleJeopardyDailyDouble.getId()
        );

        assertInvalidWager(2001);

        session.placeDailyDoubleWager(player1.getId(), 2000);

        assertEquals(2000, player1.getWager());
    }

    // -------------------------------------------------------------------------
    // Daily Double: buzzing and answering
    // -------------------------------------------------------------------------

    @Test
    void buzz_whileRevealingDailyDouble_isRejected() {
        startDailyDouble();
        session.placeDailyDoubleWager(player1.getId(), 500);

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.buzz(player2.getId())
        );

        assertEquals(GameErrorCode.INVALID_STATE, exception.getCode());
    }

    @Test
    void buzz_whileWaitingForDailyDoubleAnswer_isRejected() {
        startDailyDouble();
        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.buzz(player2.getId())
        );

        assertEquals(GameErrorCode.INVALID_STATE, exception.getCode());
    }

    @Test
    void answerClue_dailyDouble_rejectsOtherPlayer() {
        startDailyDouble();
        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.answerClue(player2.getId(), "John Adams")
        );

        assertEquals(GameErrorCode.NOT_YOUR_TURN, exception.getCode());
    }

    @Test
    void answerClue_correctDailyDouble_addsWagerAndChooserKeepsControl() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        givePlayer2Control();

        session.chooseClue(player2.getId(), dailyDoubleClue1.getId());
        session.placeDailyDoubleWager(player2.getId(), 750);
        session.finishRevealingClue();
        session.answerClue(player2.getId(), "John Adams");

        // The clue itself is worth 400, so the score must reflect the wager.
        assertEquals(200 + 750, player2.getScore());
        assertEquals(0, player1.getScore());
        assertEquals(player2.getId(), session.getPlayerChoosingClue());
    }

    @Test
    void answerClue_incorrectDailyDouble_deductsWagerAndChooserKeepsControl() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        givePlayer2Control();

        session.chooseClue(player2.getId(), dailyDoubleClue1.getId());
        session.placeDailyDoubleWager(player2.getId(), 750);
        session.finishRevealingClue();
        session.answerClue(player2.getId(), "Thomas Jefferson");

        assertEquals(200 - 750, player2.getScore());
        assertEquals(0, player1.getScore());
        assertEquals(player2.getId(), session.getPlayerChoosingClue());
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void timeoutDailyDoubleWager_thenCorrectAnswer_awardsMinimumWager() {
        startDailyDouble();

        session.timeoutDailyDoubleWager();
        session.finishRevealingClue();
        session.answerClue(player1.getId(), "John Adams");

        assertEquals(5, player1.getScore());
    }

    @Test
    void timeoutAnswer_onDailyDoubleAsFirstClue_deductsWagerFromChooserAndRevealsAnswer() {
        startDailyDouble();
        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        session.timeoutAnswer();

        assertEquals(-500, player1.getScore());
        assertEquals(0, player2.getScore());
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void timeoutAnswer_onDailyDoubleAfterBuzzedClue_deductsWagerFromChooserOnly() {
        addDailyDoubleClue();
        session.startGame(player1.getId());

        // Player 2 buzzes on a normal clue and misses, leaving player 1 in control.
        session.chooseClue(player1.getId(), jeopardyClue1.getId());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "Abraham Lincoln");
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(player1.getId(), dailyDoubleClue1.getId());
        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        session.timeoutAnswer();

        assertEquals(-500, player1.getScore());
        assertEquals(-200, player2.getScore());
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
    }

    @Test
    void dailyDouble_countsTowardRoundCompletion() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        finishJeopardyRound();

        assertEquals(GameRound.JEOPARDY, session.getRound());

        session.chooseClue(player1.getId(), dailyDoubleClue1.getId());
        session.placeDailyDoubleWager(player1.getId(), 5);
        session.finishRevealingClue();
        session.answerClue(player1.getId(), "John Adams");
        session.finishRevealingAnswer();

        assertEquals(GameRound.DOUBLE_JEOPARDY, session.getRound());
        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
    }

    // -------------------------------------------------------------------------
    // Other gameplay gaps
    // -------------------------------------------------------------------------

    @Test
    void startGame_withNoPlayers_rejectsPlayer() {
        GameSession emptySession = new GameSession(
                UUID.randomUUID(),
                session.getGame()
        );

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> emptySession.startGame(player1.getId())
        );

        assertEquals(GameErrorCode.PLAYER_NOT_IN_SESSION, exception.getCode());
        assertEquals(GameSessionState.WAITING_FOR_START, emptySession.getState());
    }

    @Test
    void startGame_rejectsPlayerNotInSession() {
        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.startGame(999L)
        );

        assertEquals(GameErrorCode.PLAYER_NOT_IN_SESSION, exception.getCode());
        assertEquals(GameSessionState.WAITING_FOR_START, session.getState());
    }

    @Test
    void answerClue_rejectsWhenNotWaitingForAnswer() {
        session.startGame(player1.getId());

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.answerClue(player1.getId(), "George Washington")
        );

        assertEquals(GameErrorCode.INVALID_STATE, exception.getCode());
    }

    @Test
    void answerClue_wrongAnswer_withPlayersRemaining_reopensBuzzer() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.getId());
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.answerClue(player2.getId(), "Abraham Lincoln");

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.buzz(player2.getId())
        );
        assertEquals(GameErrorCode.NOT_YOUR_TURN, exception.getCode());

        session.buzz(player1.getId());
        assertEquals(player1.getId(), session.getPlayerWhoBuzzed());
    }

    @Test
    void answerClue_wrongAnswer_whenAllPlayersBuzzed_revealsAnswer() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.getId());
        session.finishRevealingClue();

        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "Abraham Lincoln");
        session.buzz(player1.getId());
        session.answerClue(player1.getId(), "John Adams");

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertEquals(-200, player1.getScore());
        assertEquals(-200, player2.getScore());
    }

    @Test
    void timeoutAnswer_withPlayersRemaining_reopensBuzzer() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.getId());
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.timeoutAnswer();

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
    }

    @Test
    void finishRevealingClue_resetsPlayersAlreadyBuzzedForNextClue() {
        session.startGame(player1.getId());
        givePlayer2Control();

        session.chooseClue(player2.getId(), jeopardyClue2.getId());
        session.finishRevealingClue();

        assertTrue(session.getPlayersAlreadyBuzzed().isEmpty());

        session.buzz(player2.getId());

        assertEquals(player2.getId(), session.getPlayerWhoBuzzed());
    }

    @Test
    void answerClue_correctAnswerOnSecondRow_awardsRowValue() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue2.getId());
        session.finishRevealingClue();
        session.buzz(player1.getId());

        session.answerClue(player1.getId(), "France");

        assertEquals(400, player1.getScore());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void finishJeopardyRound() {
        playClueWithoutBuzz(jeopardyClue1.getId());
        playClueWithoutBuzz(jeopardyClue2.getId());
    }

    private void playClueWithoutBuzz(Long clueId) {
        session.chooseClue(session.getPlayerChoosingClue(), clueId);
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();
    }

    // Player 2 answers jeopardyClue1 correctly (+200) and takes control of the board.
    private void givePlayer2Control() {
        session.chooseClue(player1.getId(), jeopardyClue1.getId());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "George Washington");
        session.finishRevealingAnswer();
    }

    private void startDailyDouble() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), dailyDoubleClue1.getId());
    }

    private void assertInvalidWager(int wager) {
        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeDailyDoubleWager(player1.getId(), wager)
        );

        assertEquals(GameErrorCode.INVALID_WAGER, exception.getCode());
    }

    private void addDoubleJeopardyDailyDouble() {
        Category doubleHistory = session.getGame().getCategories().get(2);

        doubleJeopardyDailyDouble = new Clue(
                "He was the third president of the United States.",
                "Thomas Jefferson",
                (short) 2,
                ClueType.TEXT,
                null,
                doubleHistory
        );
        ReflectionTestUtils.setField(doubleJeopardyDailyDouble, "id", 202L);

        doubleHistory.getClues().add(doubleJeopardyDailyDouble);

        session.getGame().getDailyDoubles().add(
                new GameDailyDouble(session.getGame(), doubleJeopardyDailyDouble)
        );
    }

}
