package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A session whose host runs the show as Trebek (HostMode.TREBEK). */
class GameSessionTrebekTest {

    private static final Long TREBEK = 1L;
    private static final Long ANA = 2L;
    private static final Long BEN = 3L;

    private final BoardClue washington = new BoardClue(101L, 1, "First president?", "George Washington", 200, false);
    private final BoardClue adams = new BoardClue(102L, 2, "Second president?", "John Adams", 400, true);
    private final BoardClue france = new BoardClue(103L, 1, "Paris is its capital.", "France", 200, false);
    private final BoardClue mars = new BoardClue(201L, 1, "The Red Planet.", "Mars", 400, false);
    private final BoardClue finalClue = new BoardClue(301L, 1, "City of light.", "Paris", 2000, false);

    private GameSession session;
    private GameSessionPlayer ana;
    private GameSessionPlayer ben;

    @BeforeEach
    void setUp() {
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("PRESIDENTS", List.of(washington, adams)),
                        new BoardCategory("COUNTRIES", List.of(france))
                )),
                new BoardRound(GameRound.DOUBLE_JEOPARDY, List.of(
                        new BoardCategory("PLANETS", List.of(mars))
                )),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("CITIES", List.of(finalClue))
                ))
        )));

        session.addPlayer(new GameSessionPlayer(TREBEK, "Alex", PlayerTokens.generate()));
        ana = new GameSessionPlayer(ANA, "Ana", PlayerTokens.generate());
        ben = new GameSessionPlayer(BEN, "Ben", PlayerTokens.generate());
        session.addPlayer(ana);
        session.addPlayer(ben);
        session.updateHostMode(TREBEK, HostMode.TREBEK);
    }

    // -------------------------------------------------------------------------
    // Lobby
    // -------------------------------------------------------------------------

    @Test
    void becomingTrebek_movesTheHostOutOfPlayers() {
        assertEquals(HostMode.TREBEK, session.getHostMode());
        assertEquals(TREBEK, session.getTrebek().getId());
        assertEquals(TREBEK, session.getHostPlayerId());
        assertEquals(List.of(ANA, BEN), ids(session.getPlayers()));
        assertEquals(List.of(ANA, BEN, TREBEK), ids(session.getMembers()));
    }

    @Test
    void becomingAContestantAgain_putsTheHostBackInJoinOrder() {
        session.updateHostMode(TREBEK, HostMode.CONTESTANT);

        assertEquals(HostMode.CONTESTANT, session.getHostMode());
        assertNull(session.getTrebek());
        assertEquals(List.of(TREBEK, ANA, BEN), ids(session.getPlayers()));
    }

    @Test
    void onlyTheHostCanChooseTheMode_andOnlyInTheLobby() {
        assertError(GameErrorCode.NOT_HOST, () -> session.updateHostMode(ANA, HostMode.CONTESTANT));

        session.startGame(TREBEK);

        assertError(GameErrorCode.INVALID_STATE, () -> session.updateHostMode(TREBEK, HostMode.CONTESTANT));
        assertError(GameErrorCode.INVALID_STATE, () -> session.updateTimerModes(TREBEK, GameSessionTimerModes.defaults()));
    }

    @Test
    void timerModes_startAtTheirDefaults_andCanBeChanged() {
        GameSessionTimerModes modes = GameSessionTimerModes.defaults();
        assertEquals(TimerMode.TREBEK, modes.of(GameSessionState.REVEALING_CLUE));
        assertEquals(TimerMode.AUTOMATIC, modes.of(GameSessionState.WAITING_FOR_BUZZER));
        assertNull(modes.of(GameSessionState.CLUE_PICKED));

        GameSessionTimerModes allAutomatic = new GameSessionTimerModes(
                TimerMode.AUTOMATIC, TimerMode.AUTOMATIC, TimerMode.AUTOMATIC, TimerMode.AUTOMATIC, TimerMode.AUTOMATIC,
                TimerMode.AUTOMATIC, TimerMode.AUTOMATIC, TimerMode.AUTOMATIC, TimerMode.AUTOMATIC);
        session.updateTimerModes(TREBEK, allAutomatic);

        assertEquals(allAutomatic, session.getTimerModes());
    }

    @Test
    void start_needsAPlayerBesidesTrebek() {
        GameSession alone = new GameSession(SessionCode.random(), session.getBoard());
        alone.addPlayer(new GameSessionPlayer(TREBEK, "Alex", PlayerTokens.generate()));
        alone.updateHostMode(TREBEK, HostMode.TREBEK);

        InvalidGameActionException error = assertError(GameErrorCode.INVALID_STATE, () -> alone.startGame(TREBEK));
        assertEquals("Nobody is playing yet!", error.getMessage());
    }

    @Test
    void start_givesControlToTheFirstPlayerAfterTrebek() {
        session.startGame(TREBEK);

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
        assertEquals(ANA, session.getPlayerChoosingClue());
    }

    @Test
    void trebekDoesntPlay() {
        session.startGame(TREBEK);
        session.chooseClue(ANA, washington.id());
        session.advance(TREBEK);
        session.advance(TREBEK);

        assertError(GameErrorCode.NOT_YOUR_TURN, () -> session.buzz(TREBEK));
        assertError(GameErrorCode.NOT_YOUR_TURN, () -> session.answerClue(TREBEK, "George Washington"));
    }

    @Test
    void trebekActions_needATrebekSession_andTrebek() {
        assertError(GameErrorCode.NOT_HOST, () -> session.advance(ANA));

        GameSession classic = new GameSession(SessionCode.random(), session.getBoard());
        classic.addPlayer(new GameSessionPlayer(TREBEK, "Alex", PlayerTokens.generate()));
        classic.startGame(TREBEK);

        InvalidGameActionException error = assertError(GameErrorCode.INVALID_STATE, () -> classic.advance(TREBEK));
        assertEquals("Only a Trebek host can do that!", error.getMessage());
        assertError(GameErrorCode.INVALID_STATE, () -> classic.adjustScore(TREBEK, TREBEK, 100));
    }

    // -------------------------------------------------------------------------
    // A picked clue
    // -------------------------------------------------------------------------

    @Test
    void choosingAClue_waitsForTrebekToShowIt() {
        session.startGame(TREBEK);
        session.chooseClue(ANA, washington.id());

        assertEquals(GameSessionState.CLUE_PICKED, session.getState());
        assertTrue(session.getPlayedClues().contains(washington.id()));

        session.advance(TREBEK);

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
    }

    @Test
    void showingAPickedDailyDouble_startsTheWager() {
        session.startGame(TREBEK);
        session.chooseClue(ANA, adams.id());
        session.advance(TREBEK);

        assertEquals(GameSessionState.WAITING_FOR_DAILY_DOUBLE_WAGER, session.getState());
    }

    @Test
    void trebek_canChooseForTheChooser_whoKeepsControl() {
        session.startGame(TREBEK);
        session.chooseClue(TREBEK, france.id());

        assertEquals(GameSessionState.CLUE_PICKED, session.getState());
        assertEquals(france.id(), session.getChosenClueId());
        assertEquals(ANA, session.getPlayerChoosingClue());

        InvalidGameActionException error = assertError(GameErrorCode.TOO_LATE, () -> session.chooseClue(ANA, washington.id()));
        assertEquals("Trebek chose a clue for you!", error.getMessage());
    }

    @Test
    void advancingWhileChoosing_choosesTheTopClueForTheChooser() {
        session.startGame(TREBEK);
        session.advance(TREBEK);

        assertEquals(GameSessionState.CLUE_PICKED, session.getState());
        assertEquals(washington.id(), session.getChosenClueId());
        assertEquals(ANA, session.getPlayerChoosingClue());
    }

    @Test
    void returnClue_putsThePickedClueBack() {
        session.startGame(TREBEK);
        session.chooseClue(ANA, washington.id());
        long phase = session.getPhaseNumber();

        session.returnClue(TREBEK);

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
        assertTrue(session.getAvailableClues().contains(washington.id()));
        assertEquals(ANA, session.getPlayerChoosingClue());
        assertNotEquals(phase, session.getPhaseNumber());
    }

    @Test
    void returnClue_onlyWhileAClueIsPicked() {
        session.startGame(TREBEK);

        assertError(GameErrorCode.INVALID_STATE, () -> session.returnClue(TREBEK));
    }

    @Test
    void trebek_canPlaceTheDailyDoubleWagerForTheChooser() {
        session.startGame(TREBEK);
        session.chooseClue(ANA, adams.id());
        session.advance(TREBEK);

        assertError(GameErrorCode.INVALID_WAGER, () -> session.placeDailyDoubleWager(TREBEK, 5000));
        session.placeDailyDoubleWager(TREBEK, 300);

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        assertEquals(300, session.getDailyDoubleWager());

        // Then the chooser answers, without buzzing.
        session.advance(TREBEK);
        assertEquals(GameSessionState.WAITING_FOR_ANSWER, session.getState());
        assertEquals(ANA, session.getPlayerAnswering());
    }

    // -------------------------------------------------------------------------
    // Answers and rulings
    // -------------------------------------------------------------------------

    @Test
    void anAnswer_waitsUnscoredForTrebeksRuling() {
        buzzIn(ANA, washington);
        session.answerClue(ANA, "George Washington");

        assertEquals(GameSessionState.WAITING_FOR_RULING, session.getState());
        assertEquals(0, ana.getScore());
        assertEquals(List.of(), session.getClueAnswers());
        assertEquals(ANA, session.getPlayerAnswering());
        assertEquals(Set.of(ANA), session.getPlayersLockedOut());

        ClueAnswer pending = session.getPendingAnswer();
        assertEquals("George Washington", pending.answer());
        assertTrue(pending.correct(), "the check suggests it's right");
        assertFalse(pending.ruledByTrebek());
    }

    @Test
    void advance_waitsForTheRuling() {
        buzzIn(ANA, washington);
        session.answerClue(ANA, "George Washington");

        InvalidGameActionException error = assertError(GameErrorCode.INVALID_STATE, () -> session.advance(TREBEK));
        assertEquals("Rule on the answer first!", error.getMessage());
    }

    @Test
    void aCorrectRuling_scoresAndGivesControl() {
        buzzIn(BEN, washington);
        session.answerClue(BEN, "Washington");

        session.rule(TREBEK, true);

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertEquals(200, ben.getScore());
        assertEquals(BEN, session.getPlayerChoosingClue());
        assertNull(session.getPendingAnswer());

        ClueAnswer ruled = session.getClueAnswers().getFirst();
        assertEquals("Washington", ruled.answer());
        assertTrue(ruled.correct());
        assertTrue(ruled.ruledByTrebek());
    }

    @Test
    void trebeksRuling_overridesTheCheck() {
        buzzIn(ANA, washington);
        session.answerClue(ANA, "Lincoln");

        assertFalse(session.getPendingAnswer().correct());
        session.rule(TREBEK, true);

        ClueAnswer ruled = session.getClueAnswers().getFirst();
        assertTrue(ruled.correct());
        assertEquals(200, ana.getScore());
        // The check didn't match it, so there's nothing to show as matched.
        assertNull(ruled.matchedAnswers());
        assertNull(ruled.alsoAccepted());
    }

    @Test
    void aWrongRuling_letsTheOthersBuzz_thenRevealsWhenNobodyIsLeft() {
        buzzIn(ANA, washington);
        session.answerClue(ANA, "George Washington");
        session.rule(TREBEK, false);

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        assertEquals(-200, ana.getScore());
        ClueAnswer ruled = session.getClueAnswers().getFirst();
        assertFalse(ruled.correct());
        assertNull(ruled.matchedAnswers(), "a wrong ruling never shows what matched");

        session.buzz(BEN);
        session.answerClue(BEN, "Adams");
        session.rule(TREBEK, false);

        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState());
        assertEquals(-200, ben.getScore());
    }

    @Test
    void trebek_canRuleBeforeAnAnswerArrives_whichClosesTyping() {
        buzzIn(ANA, washington);

        session.rule(TREBEK, true);

        ClueAnswer ruled = session.getClueAnswers().getFirst();
        assertNull(ruled.answer());
        assertNull(ruled.phrasedAsQuestion());
        assertTrue(ruled.correct());
        assertEquals(200, ana.getScore());

        InvalidGameActionException error = assertError(GameErrorCode.TOO_LATE, () -> session.answerClue(ANA, "Washington"));
        assertEquals("Trebek already ruled on your answer!", error.getMessage());
    }

    @Test
    void runningOutOfTime_waitsForTheRulingWithoutAnAnswer() {
        buzzIn(ANA, washington);

        session.timeoutAnswer();

        assertEquals(GameSessionState.WAITING_FOR_RULING, session.getState());
        assertTrue(session.getPendingAnswer().ranOutOfTime());
        assertEquals(0, ana.getScore());
        assertError(GameErrorCode.TOO_LATE, () -> session.answerClue(ANA, "Washington"));

        // Trebek closing typing does the same.
        session.rule(TREBEK, false);
        session.buzz(BEN);
        session.advance(TREBEK);
        assertEquals(GameSessionState.WAITING_FOR_RULING, session.getState());
        assertTrue(session.getPendingAnswer().ranOutOfTime());
    }

    @Test
    void rule_onlyWhileAnswering() {
        session.startGame(TREBEK);

        assertError(GameErrorCode.INVALID_STATE, () -> session.rule(TREBEK, true));
    }

    @Test
    void doubleJeopardy_aBareAnswerRuledCorrect_stillShowsWhatItMatched() {
        playOutTheJeopardyRound();
        session.chooseClue(session.getPlayerChoosingClue(), mars.id());
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.buzz(ANA);
        session.answerClue(ANA, "Mars");

        // Classic Double Jeopardy would score it wrong: no question form.
        ClueAnswer pending = session.getPendingAnswer();
        assertFalse(pending.correct());
        assertFalse(pending.phrasedAsQuestion());

        session.rule(TREBEK, true);

        ClueAnswer ruled = session.getClueAnswers().getFirst();
        assertTrue(ruled.correct());
        assertFalse(ruled.phrasedAsQuestion());
        assertEquals(List.of("Mars"), ruled.matchedAnswers());
        assertEquals(List.of(), ruled.alsoAccepted());
    }

    // -------------------------------------------------------------------------
    // Advance through a clue
    // -------------------------------------------------------------------------

    @Test
    void advance_movesThroughEveryPhaseOfAClue() {
        session.startGame(TREBEK);
        session.chooseClue(ANA, washington.id());

        session.advance(TREBEK);
        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        session.advance(TREBEK);
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        session.advance(TREBEK);
        assertEquals(GameSessionState.REVEALING_ANSWER, session.getState(), "nobody buzzed");
        session.advance(TREBEK);
        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
    }

    @Test
    void advance_isRejectedOutsideTheGame() {
        assertError(GameErrorCode.INVALID_STATE, () -> session.advance(TREBEK));
    }

    // -------------------------------------------------------------------------
    // Adjusting scores
    // -------------------------------------------------------------------------

    @Test
    void adjustScore_changesTheScoreAndTheJoinScore() {
        session.startGame(TREBEK);

        session.adjustScore(TREBEK, BEN, -300);
        assertEquals(-300, ben.getScore());
        assertEquals(-300, session.getJoinScore());

        session.adjustScore(TREBEK, BEN, 500);
        assertEquals(200, ben.getScore());
        assertEquals(0, session.getJoinScore());
    }

    @Test
    void adjustScore_rejectsTheLobby_unknownPlayers_andZero() {
        assertError(GameErrorCode.INVALID_STATE, () -> session.adjustScore(TREBEK, ANA, 100));

        session.startGame(TREBEK);

        assertError(GameErrorCode.PLAYER_NOT_FOUND, () -> session.adjustScore(TREBEK, 99L, 100));
        assertError(GameErrorCode.PLAYER_NOT_FOUND, () -> session.adjustScore(TREBEK, TREBEK, 100));
        assertError(GameErrorCode.INVALID_REQUEST, () -> session.adjustScore(TREBEK, ANA, 0));
        assertError(GameErrorCode.INVALID_REQUEST, () -> session.adjustScore(TREBEK, ANA, 100_001));
        assertError(GameErrorCode.NOT_HOST, () -> session.adjustScore(ANA, BEN, 100));
    }

    @Test
    void adjustScore_worksAfterTheGame() {
        playToFinalJeopardyReveal();
        while (session.getState() != GameSessionState.FINISHED_GAME) {
            ruleEveryoneWrong();
            session.advance(TREBEK);
        }

        int before = ana.getScore();
        session.adjustScore(TREBEK, ANA, 1000);

        assertEquals(before + 1000, ana.getScore());
    }

    // -------------------------------------------------------------------------
    // Pausing
    // -------------------------------------------------------------------------

    @Test
    void pause_freezesTheTimeLeft_andResumeRestartsIt() {
        session.startGame(TREBEK);
        session.startPhaseTimer(Duration.ofSeconds(30), 0);

        assertTrue(session.pause(TREBEK, Duration.ofSeconds(10).toNanos()));
        assertTrue(session.isPaused());
        assertEquals(Duration.ofSeconds(20), session.getPhaseRemaining(Duration.ofSeconds(25).toNanos()));
        assertFalse(session.pause(TREBEK, 0), "pausing again changes nothing");

        assertTrue(session.resume(TREBEK, Duration.ofSeconds(100).toNanos()));
        assertFalse(session.isPaused());
        assertEquals(Duration.ofSeconds(15), session.getPhaseRemaining(Duration.ofSeconds(105).toNanos()));
        assertFalse(session.resume(TREBEK, 0), "resuming again changes nothing");
    }

    @Test
    void aPhaseStartedWhilePaused_keepsItsFullTime() {
        session.startGame(TREBEK);
        session.pause(TREBEK, 0);

        session.startPhaseTimer(Duration.ofSeconds(10), Duration.ofSeconds(1).toNanos());

        assertEquals(Duration.ofSeconds(10), session.getPhaseRemaining(Duration.ofSeconds(60).toNanos()));
    }

    @Test
    void actionsStillWorkWhilePaused() {
        session.startGame(TREBEK);
        session.pause(TREBEK, 0);

        session.chooseClue(ANA, washington.id());
        session.advance(TREBEK);

        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        assertTrue(session.isPaused());
    }

    @Test
    void pause_onlyDuringTheGame() {
        assertError(GameErrorCode.INVALID_STATE, () -> session.pause(TREBEK, 0));
    }

    // -------------------------------------------------------------------------
    // Final Jeopardy
    // -------------------------------------------------------------------------

    @Test
    void finalJeopardy_isRuledOnlyOnceAnsweringIsOver() {
        playToFinalJeopardy();
        session.advance(TREBEK);
        session.answerFinalJeopardy(ANA, "What is Paris?");

        assertError(GameErrorCode.INVALID_STATE, () -> session.ruleFinal(TREBEK, ANA, true));
    }

    @Test
    void finalJeopardy_revealsOnePlayerAtATime_lowestScoreFirst() {
        playToFinalJeopardy();
        // Ana 400, Ben 200.
        session.placeFinalJeopardyWager(ANA, 400);
        session.placeFinalJeopardyWager(BEN, 100);
        session.advance(TREBEK);
        session.answerFinalJeopardy(ANA, "What is Paris?");
        session.answerFinalJeopardy(BEN, "What is Lyon?");
        session.advance(TREBEK);

        assertEquals(GameSessionState.FINAL_JEOPARDY_REVEALING, session.getState());
        assertEquals(List.of(BEN, ANA), session.getRevealOrder());
        assertEquals(FinalRevealStep.HIDDEN, session.getRevealStep(BEN));
        assertEquals(7, session.getRevealStepCount());

        session.advance(TREBEK);
        assertEquals(FinalRevealStep.WAGER, session.getRevealStep(BEN));
        session.advance(TREBEK);
        assertEquals(FinalRevealStep.ANSWER, session.getRevealStep(BEN));

        InvalidGameActionException error = assertError(GameErrorCode.INVALID_STATE, () -> session.advance(TREBEK));
        assertEquals("Rule on Ben's answer first!", error.getMessage());

        // Trebek is generous: Lyon counts.
        session.ruleFinal(TREBEK, BEN, true);
        session.advance(TREBEK);
        assertEquals(FinalRevealStep.AWARDED, session.getRevealStep(BEN));
        assertEquals(300, ben.getScore(), "scores change at each award");
        assertEquals(400, ana.getScore());
        assertEquals(FinalRevealStep.HIDDEN, session.getRevealStep(ANA));

        assertError(GameErrorCode.INVALID_STATE, () -> session.ruleFinal(TREBEK, BEN, false));

        session.ruleFinal(TREBEK, ANA, false);
        session.ruleFinal(TREBEK, ANA, true);  // changed before the award
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.advance(TREBEK);
        assertEquals(800, ana.getScore());

        session.advance(TREBEK);
        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertEquals(FinalRevealStep.AWARDED, session.getRevealStep(ANA));
    }

    @Test
    void finalJeopardyRevealsClock_waitsAtAnUnruledAward() {
        playToFinalJeopardyReveal();
        Long first = session.getRevealOrder().getFirst();
        session.timeoutFinalJeopardyReveal();
        session.timeoutFinalJeopardyReveal();
        long phase = session.getPhaseNumber();

        session.timeoutFinalJeopardyReveal();

        assertTrue(session.isRevealWaitingForRuling());
        assertEquals(FinalRevealStep.ANSWER, session.getRevealStep(first));
        assertNotEquals(phase, session.getPhaseNumber(), "the clock stops: a phase without a timer");

        session.ruleFinal(TREBEK, first, false);

        assertFalse(session.isRevealWaitingForRuling());
        assertEquals(FinalRevealStep.AWARDED, session.getRevealStep(first));
    }

    @Test
    void finalJeopardyWagers_areCappedWhenWageringCloses() {
        playToFinalJeopardy();
        session.placeFinalJeopardyWager(ANA, 400);
        session.adjustScore(TREBEK, ANA, -300);

        session.advance(TREBEK);

        assertEquals(100, session.getFinalJeopardy().getWager(ANA));
    }

    @Test
    void trebekDoesntPlayFinalJeopardy() {
        playToFinalJeopardy();

        assertError(GameErrorCode.NOT_YOUR_TURN, () -> session.placeFinalJeopardyWager(TREBEK, 0));
    }

    // -------------------------------------------------------------------------
    // Leaving and away
    // -------------------------------------------------------------------------

    @Test
    void trebekLeavingDuringTheGame_endsIt() {
        session.startGame(TREBEK);

        session.leave(TREBEK);

        assertEquals(GameSessionState.CLOSED, session.getState());
        assertEquals(SessionCloseReason.TREBEK_LEFT, session.getCloseReason());
    }

    @Test
    void trebekLeavingTheLobby_passesTheHostToAContestant() {
        session.leave(TREBEK);

        assertEquals(GameSessionState.WAITING_FOR_START, session.getState());
        assertEquals(HostMode.CONTESTANT, session.getHostMode());
        assertNull(session.getTrebek());
        assertEquals(ANA, session.getHostPlayerId());
    }

    @Test
    void trebekLeavingAfterTheGame_keepsTheResults() {
        playToFinalJeopardyReveal();
        while (session.getState() != GameSessionState.FINISHED_GAME) {
            ruleEveryoneWrong();
            session.advance(TREBEK);
        }

        session.leave(TREBEK);

        assertEquals(GameSessionState.FINISHED_GAME, session.getState());
        assertEquals(HostMode.CONTESTANT, session.getHostMode());
        assertEquals(ANA, session.getHostPlayerId());
        assertFalse(session.isAbandoned());
    }

    @Test
    void trebekAlone_keepsTheLobby_butNotTheGame() {
        session.leave(ANA);
        session.leave(BEN);

        assertFalse(session.isAbandoned(), "Trebek can wait in the lobby for players");

        GameSession started = new GameSession(SessionCode.random(), session.getBoard());
        started.addPlayer(new GameSessionPlayer(TREBEK, "Alex", PlayerTokens.generate()));
        started.addPlayer(new GameSessionPlayer(ANA, "Ana", PlayerTokens.generate()));
        started.updateHostMode(TREBEK, HostMode.TREBEK);
        started.startGame(TREBEK);
        started.chooseClue(ANA, washington.id());

        started.leave(ANA);

        assertTrue(started.isAbandoned(), "nobody is left to play");
    }

    @Test
    void trebekCountsWhenEveryoneIsAway() {
        session.setAway(ANA, true);
        session.setAway(BEN, true);
        assertFalse(session.isEveryoneAway());

        assertTrue(session.setAway(TREBEK, true));
        assertTrue(session.getTrebek().isAway());
        assertTrue(session.isEveryoneAway());
    }

    @Test
    void trebekBeingAway_doesntStopTheGame() {
        session.startGame(TREBEK);
        session.setAway(TREBEK, true);

        session.chooseClue(ANA, washington.id());

        assertEquals(GameSessionState.CLUE_PICKED, session.getState());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void buzzIn(Long playerId, BoardClue clue) {
        session.startGame(TREBEK);
        session.chooseClue(ANA, clue.id());
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.buzz(playerId);
    }

    // Ana answers Washington (200) and Adams (Daily Double, 200), Ben France
    // (200): Ana 400, Ben 200, then Double Jeopardy.
    private void playOutTheJeopardyRound() {
        buzzIn(ANA, washington);
        session.answerClue(ANA, "Washington");
        session.rule(TREBEK, true);
        session.advance(TREBEK);

        session.chooseClue(ANA, adams.id());
        session.advance(TREBEK);
        session.placeDailyDoubleWager(ANA, 200);
        session.advance(TREBEK);
        session.answerClue(ANA, "Adams");
        session.rule(TREBEK, true);
        session.advance(TREBEK);

        session.chooseClue(ANA, france.id());
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.buzz(BEN);
        session.answerClue(BEN, "France");
        session.rule(TREBEK, true);
        session.advance(TREBEK);

        assertEquals(GameRound.DOUBLE_JEOPARDY, session.getRound());
    }

    // Double Jeopardy's clue goes unanswered.
    private void playToFinalJeopardy() {
        playOutTheJeopardyRound();
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.advance(TREBEK);

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
        assertEquals(400, ana.getScore());
        assertEquals(200, ben.getScore());
    }

    private void playToFinalJeopardyReveal() {
        playToFinalJeopardy();
        session.advance(TREBEK);
        session.advance(TREBEK);
    }

    private void ruleEveryoneWrong() {
        for (Long playerId : session.getRevealOrder()) {
            if (session.getRevealStep(playerId) != FinalRevealStep.AWARDED) {
                session.ruleFinal(TREBEK, playerId, false);
            }
        }
    }

    private static List<Long> ids(List<GameSessionPlayer> players) {
        return players.stream().map(GameSessionPlayer::getId).toList();
    }

    private static InvalidGameActionException assertError(GameErrorCode code, Executable action) {
        InvalidGameActionException error = assertThrows(InvalidGameActionException.class, action);
        assertEquals(code, error.getCode());
        return error;
    }
}
