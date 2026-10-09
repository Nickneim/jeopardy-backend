package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionTest {


    private GameSession session;

    private GameSessionPlayer player1;
    private GameSessionPlayer player2;

    private BoardClue jeopardyClue1;
    private BoardClue jeopardyClue2;
    private BoardClue dailyDoubleClue1;
    private BoardClue doubleJeopardyClue1;
    private BoardClue doubleJeopardyDailyDouble;
    private BoardClue finalJeopardyClue;


    @Test
    void addPlayer_addsPlayerToSession() {
        UUID sessionId = UUID.randomUUID();
        GameSession session =
                new GameSession(sessionId, this.session.getBoard());

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
        jeopardyClue1 = new BoardClue(
                101L,
                1,
                "Who was the first president of the United States?",
                "George Washington",
                200,
                false
        );

        jeopardyClue2 = new BoardClue(
                102L,
                2,
                "This country has the city of Paris as its capital.",
                "France",
                400,
                false
        );

        // Daily Doubles sit on row 5, so they are each round's highest value.
        dailyDoubleClue1 = new BoardClue(
                103L,
                5,
                "Who was the second president of the United States?",
                "John Adams",
                1000,
                true
        );

        doubleJeopardyClue1 = new BoardClue(
                201L,
                1,
                "This planet is known as the Red Planet.",
                "Mars",
                400,
                false
        );

        doubleJeopardyDailyDouble = new BoardClue(
                202L,
                5,
                "He was the third president of the United States.",
                "Thomas Jefferson",
                2000,
                true
        );

        finalJeopardyClue = new BoardClue(
                301L,
                1,
                "This historical event began in 1775.",
                "The American Revolution",
                2000,
                false
        );

        player1 = new GameSessionPlayer(1L, "Nico");
        player2 = new GameSessionPlayer(2L, "John");

        useBoard(false, false);
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
                jeopardyClue1.id()
        );

        assertEquals(
                jeopardyClue1.id(),
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
                        jeopardyClue1.id()
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
                                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                                jeopardyClue1.id()
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
                                doubleJeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                jeopardyClue1.id()
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
                Set.of(doubleJeopardyClue1.id()),
                session.getAvailableClues()
        );

        // Played clues are remembered across rounds.
        assertEquals(
                Set.of(jeopardyClue1.id(), jeopardyClue2.id()),
                session.getPlayedClues()
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
                doubleJeopardyClue1.id()
        );

        assertEquals(
                doubleJeopardyClue1.id(),
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
                doubleJeopardyClue1.id()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(
                GameRound.FINAL_JEOPARDY,
                session.getRound()
        );

        assertEquals(
                GameSessionState.FINAL_JEOPARDY_WAGERING,
                session.getState()
        );

        // The Final clue isn't chosen from the board; it is played at once.
        assertEquals(
                Set.of(),
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
                doubleJeopardyClue1.id()
        );
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.timeoutFinalJeopardyWagering();
        session.timeoutFinalJeopardyAnswering();
        session.finishFinalJeopardyReveal();

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
                doubleJeopardyClue1.id()
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
        useBoard(true, false);
    }


    @Test
    void chooseClue_forDailyDouble_waitsForWagerBeforeRevealing() {
        addDailyDoubleClue();

        session.startGame(player1.getId());

        session.chooseClue(
                player1.getId(),
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                dailyDoubleClue1.id()
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
                doubleJeopardyDailyDouble.id()
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

        session.chooseClue(player2.getId(), dailyDoubleClue1.id());
        session.placeDailyDoubleWager(player2.getId(), 750);
        session.finishRevealingClue();
        session.answerClue(player2.getId(), "John Adams");

        // The clue itself is worth 1000, so the score must reflect the wager.
        assertEquals(200 + 750, player2.getScore());
        assertEquals(0, player1.getScore());
        assertEquals(player2.getId(), session.getPlayerChoosingClue());
    }

    @Test
    void answerClue_incorrectDailyDouble_deductsWagerAndChooserKeepsControl() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        givePlayer2Control();

        session.chooseClue(player2.getId(), dailyDoubleClue1.id());
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
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "Abraham Lincoln");
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        session.chooseClue(player1.getId(), dailyDoubleClue1.id());
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

        session.chooseClue(player1.getId(), dailyDoubleClue1.id());
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
                session.getBoard()
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
        session.chooseClue(player1.getId(), jeopardyClue1.id());
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
        session.chooseClue(player1.getId(), jeopardyClue1.id());
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
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());

        session.timeoutAnswer();

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
    }

    @Test
    void finishRevealingClue_resetsPlayersAlreadyBuzzedForNextClue() {
        session.startGame(player1.getId());
        givePlayer2Control();

        session.chooseClue(player2.getId(), jeopardyClue2.id());
        session.finishRevealingClue();

        assertTrue(session.getPlayersAlreadyBuzzed().isEmpty());

        session.buzz(player2.getId());

        assertEquals(player2.getId(), session.getPlayerWhoBuzzed());
    }

    @Test
    void answerClue_correctAnswerOnSecondRow_awardsRowValue() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue2.id());
        session.finishRevealingClue();
        session.buzz(player1.getId());

        session.answerClue(player1.getId(), "France");

        assertEquals(400, player1.getScore());
    }

    // -------------------------------------------------------------------------
    // Board-driven rules
    // -------------------------------------------------------------------------

    @Test
    void getAvailableClues_excludesPlayedCluesOfTheCurrentRound() {
        session.startGame(player1.getId());

        assertEquals(
                Set.of(jeopardyClue1.id(), jeopardyClue2.id()),
                session.getAvailableClues()
        );

        session.chooseClue(player1.getId(), jeopardyClue1.id());

        assertEquals(
                Set.of(jeopardyClue2.id()),
                session.getAvailableClues()
        );
    }

    @Test
    void chooseClue_rejectsPlayedClueFromAnEarlierRound() {
        session.startGame(player1.getId());
        finishJeopardyRound();

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.chooseClue(player1.getId(), jeopardyClue1.id())
        );

        assertEquals(GameErrorCode.CLUE_NOT_FOUND, exception.getCode());
    }

    @Test
    void placeDailyDoubleWager_limitIsHighestClueValueOnTheBoardsRound() {
        BoardClue lowClue = new BoardClue(1L, 1, "Question", "Answer", 100, false);
        BoardClue dailyDouble = new BoardClue(2L, 3, "DD question", "DD answer", 300, true);

        GameSession smallSession = new GameSession(
                UUID.randomUUID(),
                new GameBoard(List.of(new BoardRound(
                        GameRound.JEOPARDY,
                        List.of(new BoardCategory("SMALL", List.of(lowClue, dailyDouble)))
                )))
        );
        smallSession.addPlayer(player1);
        smallSession.startGame(player1.getId());
        smallSession.chooseClue(player1.getId(), dailyDouble.id());

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> smallSession.placeDailyDoubleWager(player1.getId(), 301)
        );
        assertEquals(GameErrorCode.INVALID_WAGER, exception.getCode());

        smallSession.placeDailyDoubleWager(player1.getId(), 300);

        assertEquals(300, player1.getWager());
    }

    @Test
    void finishRevealingAnswer_skipsRoundsWithoutClues() {
        GameSession noDoubleJeopardy = new GameSession(
                UUID.randomUUID(),
                new GameBoard(List.of(
                        new BoardRound(GameRound.JEOPARDY, List.of(
                                new BoardCategory("HISTORY", List.of(jeopardyClue1))
                        )),
                        new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                                new BoardCategory("FINAL HISTORY", List.of(finalJeopardyClue))
                        ))
                ))
        );
        noDoubleJeopardy.addPlayer(player1);
        noDoubleJeopardy.startGame(player1.getId());

        noDoubleJeopardy.chooseClue(player1.getId(), jeopardyClue1.id());
        noDoubleJeopardy.finishRevealingClue();
        noDoubleJeopardy.timeoutBuzzer();
        noDoubleJeopardy.finishRevealingAnswer();

        assertEquals(GameRound.FINAL_JEOPARDY, noDoubleJeopardy.getRound());
        assertEquals(
                GameSessionState.FINAL_JEOPARDY_WAGERING,
                noDoubleJeopardy.getState()
        );
    }

    // -------------------------------------------------------------------------
    // Turn, buzzer and wager info for clients
    // -------------------------------------------------------------------------

    @Test
    void getPlayerAnswering_isOnlySetWhileWaitingForAnswer() {
        session.startGame(player1.getId());
        assertNull(session.getPlayerAnswering());

        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        assertNull(session.getPlayerAnswering());

        session.buzz(player2.getId());
        assertEquals(player2.getId(), session.getPlayerAnswering());

        session.answerClue(player2.getId(), "Abraham Lincoln");
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        assertNull(session.getPlayerAnswering());
    }

    @Test
    void getPlayerAnswering_isNotLeftOverFromAPreviousClue() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "George Washington");
        session.finishRevealingAnswer();

        session.chooseClue(player2.getId(), jeopardyClue2.id());
        session.finishRevealingClue();

        assertNull(session.getPlayerAnswering());
    }

    @Test
    void getPlayerAnswering_onDailyDouble_isTheChooser() {
        startDailyDouble();
        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();

        assertEquals(player1.getId(), session.getPlayerAnswering());
    }

    @Test
    void getPlayersLockedOut_coversOnlyTheClueInPlay() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "Abraham Lincoln");

        assertEquals(Set.of(player2.getId()), session.getPlayersLockedOut());

        session.timeoutBuzzer();
        assertEquals(Set.of(), session.getPlayersLockedOut());

        session.finishRevealingAnswer();
        session.chooseClue(player1.getId(), jeopardyClue2.id());

        // The underlying set still holds player 2 until this reveal finishes.
        assertEquals(Set.of(), session.getPlayersLockedOut());
    }

    @Test
    void getDailyDoubleWager_isOnlySetOncePlacedAndWhileTheDailyDoubleIsInPlay() {
        startDailyDouble();
        assertNull(session.getDailyDoubleWager());

        session.placeDailyDoubleWager(player1.getId(), 500);
        assertEquals(500, session.getDailyDoubleWager());

        session.finishRevealingClue();
        assertEquals(500, session.getDailyDoubleWager());

        session.answerClue(player1.getId(), "John Adams");
        assertEquals(500, session.getDailyDoubleWager());

        session.finishRevealingAnswer();
        assertNull(session.getDailyDoubleWager());

        // Player 1 still has a stored wager, but this clue isn't a Daily Double.
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        assertNull(session.getDailyDoubleWager());
    }

    // -------------------------------------------------------------------------
    // Phase timer
    // -------------------------------------------------------------------------

    @Test
    void phaseTimer_isUnsetForANewSession() {
        assertNull(session.getPhaseDuration());
        assertNull(session.getPhaseRemaining(0));
    }

    @Test
    void phaseTimer_countsDownFromWhenItStarted() {
        long start = 1_000_000_000L;

        session.startPhaseTimer(Duration.ofSeconds(10), start);

        assertEquals(Duration.ofSeconds(10), session.getPhaseDuration());
        assertEquals(Duration.ofSeconds(10), session.getPhaseRemaining(start));
        assertEquals(Duration.ofMillis(6500), session.getPhaseRemaining(start + 3_500_000_000L));
    }

    @Test
    void phaseTimer_neverGoesNegative() {
        session.startPhaseTimer(Duration.ofSeconds(3), 0);

        assertEquals(Duration.ZERO, session.getPhaseRemaining(5_000_000_000L));
    }

    @Test
    void phaseTimer_canBeCleared() {
        session.startPhaseTimer(Duration.ofSeconds(3), 0);
        session.clearPhaseTimer();

        assertNull(session.getPhaseDuration());
        assertNull(session.getPhaseRemaining(0));
    }

    // -------------------------------------------------------------------------
    // Clue choice timeout
    // -------------------------------------------------------------------------

    @Test
    void timeoutChoosingClue_choosesTheTopClueForTheChooser() {
        session.startGame(player1.getId());

        session.timeoutChoosingClue();

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        assertEquals(jeopardyClue1.id(), session.getChosenClueId());
        assertTrue(session.getPlayedClues().contains(jeopardyClue1.id()));
        assertEquals(player1.getId(), session.getPlayerChoosingClue());
    }

    @Test
    void timeoutChoosingClue_skipsPlayedClues() {
        session.startGame(player1.getId());
        playClueWithoutBuzz(jeopardyClue1.id());

        session.timeoutChoosingClue();

        assertEquals(jeopardyClue2.id(), session.getChosenClueId());
    }

    @Test
    void timeoutChoosingClue_choosesForWhoeverHasControl() {
        session.startGame(player1.getId());
        givePlayer2Control();

        session.timeoutChoosingClue();

        assertEquals(jeopardyClue2.id(), session.getChosenClueId());
        assertEquals(player2.getId(), session.getPlayerChoosingClue());
    }

    @Test
    void timeoutChoosingClue_prefersTheLeftmostCategoryOnTheSameRow() {
        BoardClue left = new BoardClue(1L, 2, "Left?", "Left", 400, false);
        BoardClue right = new BoardClue(2L, 2, "Right?", "Right", 400, false);
        BoardClue lower = new BoardClue(3L, 3, "Lower?", "Lower", 600, false);

        GameSession session = new GameSession(UUID.randomUUID(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("LEFT", List.of(lower, left)),
                        new BoardCategory("RIGHT", List.of(right))
                ))
        )));
        session.addPlayer(player1);
        session.startGame(player1.getId());

        session.timeoutChoosingClue();

        assertEquals(left.id(), session.getChosenClueId());
    }

    @Test
    void timeoutChoosingClue_canLandOnADailyDouble() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        playClueWithoutBuzz(jeopardyClue1.id());
        playClueWithoutBuzz(jeopardyClue2.id());

        session.timeoutChoosingClue();

        assertEquals(dailyDoubleClue1.id(), session.getChosenClueId());
        assertEquals(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER, session.getState());
    }

    @Test
    void timeoutChoosingClue_isRejectedOutsideChoosingClue() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.timeoutChoosingClue()
        );

        assertEquals(GameErrorCode.INVALID_STATE, exception.getCode());
    }

    // -------------------------------------------------------------------------
    // Answer results
    // -------------------------------------------------------------------------

    @Test
    void getClueAnswers_isEmptyUntilSomeoneAnswers() {
        assertEquals(List.of(), session.getClueAnswers());

        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());

        assertEquals(List.of(), session.getClueAnswers());
    }

    @Test
    void getClueAnswers_keepsEveryAnswerToTheClueInOrder() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player1.getId());
        session.answerClue(player1.getId(), "Abraham Lincoln");

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        assertEquals(
                List.of(new ClueAnswer(player1.getId(), "Abraham Lincoln", false)),
                session.getClueAnswers()
        );

        session.buzz(player2.getId());
        assertEquals(1, session.getClueAnswers().size());

        session.answerClue(player2.getId(), "George Washington");

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertEquals(
                List.of(
                        new ClueAnswer(player1.getId(), "Abraham Lincoln", false),
                        new ClueAnswer(player2.getId(), "George Washington", true)
                ),
                session.getClueAnswers()
        );
    }

    @Test
    void getClueAnswers_recordsARunOutClockAsANullAnswer() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.timeoutAnswer();

        assertEquals(
                List.of(new ClueAnswer(player2.getId(), null, false)),
                session.getClueAnswers()
        );
    }

    @Test
    void getClueAnswers_includesDailyDoubleAnswers() {
        startDailyDouble();
        session.placeDailyDoubleWager(player1.getId(), 500);
        session.finishRevealingClue();
        session.answerClue(player1.getId(), "Thomas Jefferson");

        assertEquals(
                List.of(new ClueAnswer(player1.getId(), "Thomas Jefferson", false)),
                session.getClueAnswers()
        );
    }

    @Test
    void getClueAnswers_lastsUntilTheNextClueIsChosen() {
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player1.getId());
        session.answerClue(player1.getId(), "George Washington");
        session.finishRevealingAnswer();

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
        assertEquals(
                List.of(new ClueAnswer(player1.getId(), "George Washington", true)),
                session.getClueAnswers()
        );

        session.chooseClue(player1.getId(), jeopardyClue2.id());

        assertEquals(List.of(), session.getClueAnswers());
    }

    @Test
    void getClueAnswers_isClearedWhenFinalJeopardyStarts() {
        session.startGame(player1.getId());
        finishJeopardyRound();
        session.chooseClue(player1.getId(), doubleJeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player1.getId());
        session.answerClue(player1.getId(), "Venus");
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
        assertEquals(List.of(), session.getClueAnswers());
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    @Test
    void finalJeopardy_showsCategoryAndClueFromTheBoard() {
        reachFinalJeopardy();

        assertEquals("FINAL HISTORY", session.getFinalJeopardyCategory().name());
        assertEquals(finalJeopardyClue, session.getFinalJeopardyClue());
    }

    @Test
    void finalJeopardy_startsAtOnceWhenItIsTheFirstRound() {
        GameSession onlyFinal = new GameSession(
                UUID.randomUUID(),
                new GameBoard(List.of(new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("FINAL HISTORY", List.of(finalJeopardyClue))
                ))))
        );
        onlyFinal.addPlayer(player1);

        onlyFinal.startGame(player1.getId());

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, onlyFinal.getState());
    }

    @Test
    void finishRevealingAnswer_finishesGameWhenThereIsNoFinalJeopardy() {
        GameSession noFinal = new GameSession(
                UUID.randomUUID(),
                new GameBoard(List.of(new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", List.of(jeopardyClue1))
                ))))
        );
        noFinal.addPlayer(player1);
        noFinal.startGame(player1.getId());

        noFinal.chooseClue(player1.getId(), jeopardyClue1.id());
        noFinal.finishRevealingClue();
        noFinal.timeoutBuzzer();
        noFinal.finishRevealingAnswer();

        assertEquals(GameSessionState.FINISHED_GAME, noFinal.getState());
    }

    @Test
    void placeFinalJeopardyWager_allowsZeroUpToScore() {
        reachFinalJeopardy();
        player1.addScore(1000);

        assertInvalidFinalWager(player1, -1);
        assertInvalidFinalWager(player1, 1001);

        session.placeFinalJeopardyWager(player1.getId(), 0);
        assertEquals(0, session.getFinalJeopardyWager(player1.getId()));

        session.placeFinalJeopardyWager(player1.getId(), 1000);
        assertEquals(1000, session.getFinalJeopardyWager(player1.getId()));
    }

    @Test
    void placeFinalJeopardyWager_withZeroOrNegativeScore_onlyAllowsZero() {
        reachFinalJeopardy();
        player2.addScore(-400);

        assertInvalidFinalWager(player1, 1);
        assertInvalidFinalWager(player2, 1);

        session.placeFinalJeopardyWager(player1.getId(), 0);
        session.placeFinalJeopardyWager(player2.getId(), 0);

        assertTrue(session.hasFinalJeopardyWager(player1.getId()));
        assertTrue(session.hasFinalJeopardyWager(player2.getId()));
    }

    @Test
    void placeFinalJeopardyWager_canBeChangedUntilWageringEnds() {
        reachFinalJeopardy();
        player1.addScore(1000);

        session.placeFinalJeopardyWager(player1.getId(), 300);
        session.placeFinalJeopardyWager(player1.getId(), 500);

        assertEquals(500, session.getFinalJeopardyWager(player1.getId()));
    }

    @Test
    void placeFinalJeopardyWager_rejectsPlayerNotInSession() {
        reachFinalJeopardy();

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeFinalJeopardyWager(999L, 0)
        );

        assertEquals(GameErrorCode.PLAYER_NOT_IN_SESSION, exception.getCode());
    }

    @Test
    void placeFinalJeopardyWager_rejectsWhenNotWagering() {
        session.startGame(player1.getId());

        InvalidGameActionException beforeFinal = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeFinalJeopardyWager(player1.getId(), 0)
        );
        assertEquals(GameErrorCode.INVALID_STATE, beforeFinal.getCode());

        finishJeopardyRound();
        playClueWithoutBuzz(doubleJeopardyClue1.id());
        session.timeoutFinalJeopardyWagering();

        InvalidGameActionException whileAnswering = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeFinalJeopardyWager(player1.getId(), 0)
        );
        assertEquals(GameErrorCode.INVALID_STATE, whileAnswering.getCode());
    }

    @Test
    void timeoutFinalJeopardyWagering_givesMissingWagersZeroAndRevealsClue() {
        reachFinalJeopardy();
        player1.addScore(1000);
        session.placeFinalJeopardyWager(player1.getId(), 600);

        session.timeoutFinalJeopardyWagering();

        assertEquals(GameSessionState.FINAL_JEOPARDY_ANSWERING, session.getState());
        assertEquals(600, session.getFinalJeopardyWager(player1.getId()));
        assertTrue(session.hasFinalJeopardyWager(player2.getId()));
        assertEquals(0, session.getFinalJeopardyWager(player2.getId()));
    }

    @Test
    void answerFinalJeopardy_rejectsWhileWagering() {
        reachFinalJeopardy();

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.answerFinalJeopardy(player1.getId(), "The American Revolution")
        );

        assertEquals(GameErrorCode.INVALID_STATE, exception.getCode());
    }

    @Test
    void answerFinalJeopardy_rejectsPlayerNotInSession() {
        reachFinalJeopardy();
        session.timeoutFinalJeopardyWagering();

        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.answerFinalJeopardy(999L, "The American Revolution")
        );

        assertEquals(GameErrorCode.PLAYER_NOT_IN_SESSION, exception.getCode());
    }

    @Test
    void answerFinalJeopardy_canBeChangedUntilAnsweringEnds() {
        reachFinalJeopardy();
        session.timeoutFinalJeopardyWagering();

        session.answerFinalJeopardy(player1.getId(), "The Civil War");
        session.answerFinalJeopardy(player1.getId(), "The American Revolution");

        assertEquals("The American Revolution", session.getFinalJeopardyAnswer(player1.getId()));
        assertTrue(session.isFinalJeopardyAnswerCorrect(player1.getId()));
    }

    @Test
    void timeoutFinalJeopardyAnswering_revealsWithoutChangingScores() {
        reachFinalJeopardy();
        player1.addScore(1000);
        session.placeFinalJeopardyWager(player1.getId(), 500);
        session.timeoutFinalJeopardyWagering();
        session.answerFinalJeopardy(player1.getId(), "The American Revolution");

        session.timeoutFinalJeopardyAnswering();

        assertEquals(GameSessionState.FINAL_JEOPARDY_REVEALING, session.getState());
        assertEquals(1000, player1.getScore());
    }

    @Test
    void finishFinalJeopardyReveal_appliesWagersAndFinishesGame() {
        reachFinalJeopardy();
        player1.addScore(1000);
        player2.addScore(800);
        GameSessionPlayer player3 = new GameSessionPlayer(3L, "Ana");
        session.addPlayer(player3);
        player3.addScore(600);

        session.placeFinalJeopardyWager(player1.getId(), 500);
        session.placeFinalJeopardyWager(player2.getId(), 300);
        session.placeFinalJeopardyWager(player3.getId(), 200);
        session.timeoutFinalJeopardyWagering();

        session.answerFinalJeopardy(player1.getId(), "The American Revolution");
        session.answerFinalJeopardy(player2.getId(), "The Civil War");
        // Player 3 never answers, which counts as wrong.
        session.timeoutFinalJeopardyAnswering();

        session.finishFinalJeopardyReveal();

        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertEquals(1500, player1.getScore());
        assertEquals(500, player2.getScore());
        assertEquals(400, player3.getScore());
    }

    @Test
    void finishFinalJeopardyReveal_withZeroWager_leavesScoreUnchanged() {
        reachFinalJeopardy();
        player1.addScore(-200);
        session.timeoutFinalJeopardyWagering();
        session.answerFinalJeopardy(player1.getId(), "The Civil War");
        session.timeoutFinalJeopardyAnswering();

        session.finishFinalJeopardyReveal();

        assertEquals(-200, player1.getScore());
    }

    @Test
    void regularActions_areRejectedDuringFinalJeopardy() {
        reachFinalJeopardy();

        assertEquals(
                GameErrorCode.INVALID_STATE,
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.buzz(player1.getId())
                ).getCode()
        );
        assertEquals(
                GameErrorCode.INVALID_STATE,
                assertThrows(
                        InvalidGameActionException.class,
                        () -> session.chooseClue(player1.getId(), finalJeopardyClue.id())
                ).getCode()
        );
    }

    // -------------------------------------------------------------------------
    // Player numbering and version
    // -------------------------------------------------------------------------

    @Test
    void addPlayerByName_numbersAfterTheHighestId() {
        // The fixture already has players 1 and 2.
        GameSessionPlayer ana = session.addPlayer("Ana");

        assertEquals(3L, ana.getId());
        assertEquals("Ana", ana.getName());
        assertEquals(List.of(player1, player2, ana), session.getPlayers());
    }

    @Test
    void addPlayerByName_startsAtOneInAnEmptySession() {
        GameSession emptySession = new GameSession(
                UUID.randomUUID(),
                session.getBoard()
        );

        assertEquals(1L, emptySession.addPlayer("Nico").getId());
        assertEquals(2L, emptySession.addPlayer("John").getId());
    }

    @Test
    void version_startsAtZeroAndGoesUpByOne() {
        assertEquals(0, session.getVersion());

        session.incrementVersion();
        session.incrementVersion();

        assertEquals(2, session.getVersion());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void finishJeopardyRound() {
        playClueWithoutBuzz(jeopardyClue1.id());
        playClueWithoutBuzz(jeopardyClue2.id());
    }

    private void reachFinalJeopardy() {
        session.startGame(player1.getId());
        finishJeopardyRound();
        playClueWithoutBuzz(doubleJeopardyClue1.id());

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
    }

    private void assertInvalidFinalWager(GameSessionPlayer player, int wager) {
        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeFinalJeopardyWager(player.getId(), wager)
        );

        assertEquals(GameErrorCode.INVALID_WAGER, exception.getCode());
    }

    private void playClueWithoutBuzz(Long clueId) {
        session.chooseClue(session.getPlayerChoosingClue(), clueId);
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();
    }

    // Player 2 answers jeopardyClue1 correctly (+200) and takes control of the board.
    private void givePlayer2Control() {
        session.chooseClue(player1.getId(), jeopardyClue1.id());
        session.finishRevealingClue();
        session.buzz(player2.getId());
        session.answerClue(player2.getId(), "George Washington");
        session.finishRevealingAnswer();
    }

    private void startDailyDouble() {
        addDailyDoubleClue();
        session.startGame(player1.getId());
        session.chooseClue(player1.getId(), dailyDoubleClue1.id());
    }

    private void assertInvalidWager(int wager) {
        InvalidGameActionException exception = assertThrows(
                InvalidGameActionException.class,
                () -> session.placeDailyDoubleWager(player1.getId(), wager)
        );

        assertEquals(GameErrorCode.INVALID_WAGER, exception.getCode());
    }

    private void addDoubleJeopardyDailyDouble() {
        useBoard(false, true);
    }

    // Boards are immutable, so adding a Daily Double means starting a new
    // session (with the same players) on a board that already has it.
    private void useBoard(
            boolean withJeopardyDailyDouble,
            boolean withDoubleJeopardyDailyDouble
    ) {
        List<BoardClue> historyClues = withJeopardyDailyDouble
                ? List.of(jeopardyClue1, dailyDoubleClue1)
                : List.of(jeopardyClue1);

        List<BoardClue> doubleHistoryClues = withDoubleJeopardyDailyDouble
                ? List.of(doubleJeopardyClue1, doubleJeopardyDailyDouble)
                : List.of(doubleJeopardyClue1);

        GameBoard board = new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", historyClues),
                        new BoardCategory("SCIENCE", List.of(jeopardyClue2))
                )),
                new BoardRound(GameRound.DOUBLE_JEOPARDY, List.of(
                        new BoardCategory("HISTORY", doubleHistoryClues)
                )),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("FINAL HISTORY", List.of(finalJeopardyClue))
                ))
        ));

        session = new GameSession(UUID.randomUUID(), board);
        session.addPlayer(player1);
        session.addPlayer(player2);
    }

}
