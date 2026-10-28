package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.controller.GameSessionBroadcaster;
import com.nickneim.jeopardy.session.controller.SessionWatchers;
import com.nickneim.jeopardy.session.controller.TrebekSubscriptions;
import com.nickneim.jeopardy.session.dto.SessionSettingsRequest;
import com.nickneim.jeopardy.session.dto.SessionTimerModesRequest;
import com.nickneim.jeopardy.session.exception.GameErrorCode;
import com.nickneim.jeopardy.session.exception.GameSessionNotFoundException;
import com.nickneim.jeopardy.session.exception.InvalidGameActionException;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.HostMode;
import com.nickneim.jeopardy.session.model.SessionCloseReason;
import com.nickneim.jeopardy.session.model.TimerMode;
import com.nickneim.jeopardy.session.security.PlayerConnections;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import com.nickneim.jeopardy.session.security.PlayerTokenRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** GameSessionService with a Trebek host: timer modes, pausing, leaving and Trebek's view. */
class GameSessionServiceTrebekTest {

    private static final Long CLUE_ID = 10L;
    private static final Long FINAL_CLUE_ID = 20L;

    private GameSessionService service;
    private GameSessionTimer timer;
    private GameSessionBroadcaster broadcaster;
    private PlayerTokenRegistry playerTokens;

    private GameSession session;
    private GameSessionPlayer trebek;
    private GameSessionPlayer ana;
    private GameSessionPlayer ben;

    @BeforeEach
    void setUp() {
        timer = mock(GameSessionTimer.class);
        broadcaster = mock(GameSessionBroadcaster.class);
        playerTokens = new PlayerTokenRegistry();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T12:00:00Z"));

        service = new GameSessionService(
                timer, broadcaster, playerTokens, mock(PlayerConnections.class), mock(SessionWatchers.class), clock);

        session = TestGameSessions.createSession(service, new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory("Category", List.of(
                        new BoardClue(CLUE_ID, 1, "Question", "Correct answer", 200, false)
                )))),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(new BoardCategory("Final", List.of(
                        new BoardClue(FINAL_CLUE_ID, 1, "Final question", "Final answer", 2000, false)
                ))))
        )));
        trebek = service.joinSession(session.getId(), "Alex");
        ana = service.joinSession(session.getId(), "Ana");
        ben = service.joinSession(session.getId(), "Ben");
        service.updateSettings(as(trebek), new SessionSettingsRequest(null, HostMode.TREBEK, null));
    }

    @Test
    void updateSettings_needsAtLeastOneSetting() {
        InvalidGameActionException error = assertThrows(
                InvalidGameActionException.class,
                () -> service.updateSettings(as(trebek), new SessionSettingsRequest(null, null, null))
        );

        assertEquals(GameErrorCode.INVALID_REQUEST, error.getCode());
        assertEquals("At least one setting must be set", error.getMessage());
    }

    @Test
    void updateSettings_changesOnlyTheTimerModesItNames() {
        service.updateSettings(as(trebek), new SessionSettingsRequest(null, null,
                new SessionTimerModesRequest(null, TimerMode.AUTOMATIC, null, null, null, null, null, null, null, null, null)));

        assertEquals(TimerMode.AUTOMATIC, session.getTimerModes().clueReveal());
        assertEquals(TimerMode.TREBEK, session.getTimerModes().answerReveal());
        assertEquals(HostMode.TREBEK, session.getHostMode());
    }

    @Test
    void phasesWaitingForTrebek_haveNoTimer_andAutomaticOnesDo() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        service.chooseClue(as(ana), CLUE_ID);

        assertEquals(GameSessionState.CLUE_PICKED, session.getState());
        assertNull(session.getPhaseDuration(), "a picked clue always waits for Trebek");

        clearInvocations(timer);
        service.advance(as(trebek));

        // The clue reveal is TREBEK by default.
        assertEquals(GameSessionState.REVEALING_CLUE, session.getState());
        assertNull(session.getPhaseDuration());
        verify(timer, never()).schedule(any(), any(), any());

        service.advance(as(trebek));

        // The buzzer is AUTOMATIC by default.
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        assertEquals(Duration.ofSeconds(10), session.getPhaseDuration());
        verify(timer).schedule(eq(session.getId()), eq(Duration.ofSeconds(10)), any());
    }

    @Test
    void theAnswerTimer_endsInARuling_neverInAScore() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        service.chooseClue(as(ana), CLUE_ID);
        service.advance(as(trebek));
        service.advance(as(trebek));
        service.buzz(as(ana));

        Runnable answerTimeout = scheduled(Duration.ofSeconds(15));
        answerTimeout.run();

        assertEquals(GameSessionState.WAITING_FOR_RULING, session.getState());
        assertEquals(0, ana.getScore());
        assertNull(session.getPhaseDuration());
    }

    @Test
    void pause_cancelsTheTimer_andResumeArmsItWithTheTimeLeft() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        verify(timer).schedule(eq(session.getId()), eq(Duration.ofSeconds(30)), any());
        clearInvocations(timer, broadcaster);

        service.pause(as(trebek));

        assertTrue(session.isPaused());
        verify(timer).cancel(session.getId());
        verify(broadcaster).broadcast(session);

        clearInvocations(timer, broadcaster);
        service.pause(as(trebek));
        verifyNoInteractions(timer, broadcaster);

        service.resume(as(trebek));

        assertFalse(session.isPaused());
        ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);
        verify(timer).schedule(eq(session.getId()), delay.capture(), any());
        assertTrue(delay.getValue().compareTo(Duration.ofSeconds(30)) <= 0);
        assertTrue(delay.getValue().compareTo(Duration.ofSeconds(29)) > 0);
        verify(broadcaster).broadcast(session);
    }

    @Test
    void aPhaseThatStartsWhilePaused_isNotArmedUntilResumed() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        service.pause(as(trebek));
        service.chooseClue(as(ana), CLUE_ID);
        service.advance(as(trebek));
        clearInvocations(timer);

        service.advance(as(trebek));

        assertEquals(GameSessionState.WAITING_FOR_BUZZER, session.getState());
        assertEquals(Duration.ofSeconds(10), session.getPhaseRemaining(System.nanoTime() + Duration.ofMinutes(1).toNanos()));
        verify(timer, never()).schedule(any(), any(), any());

        service.resume(as(trebek));

        verify(timer).schedule(eq(session.getId()), eq(Duration.ofSeconds(10)), any());
    }

    @Test
    void aTimerThatFiresAfterThePause_doesNothing() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        Runnable clueChoiceTimeout = scheduled(Duration.ofSeconds(30));

        service.pause(as(trebek));
        clueChoiceTimeout.run();

        assertEquals(GameSessionState.CHOOSING_CLUE, session.getState());
    }

    @Test
    void automaticFinalJeopardyReveal_splitsEachPlayersTimeOverTheirSteps() {
        service.updateSettings(as(trebek), new SessionSettingsRequest(null, null,
                new SessionTimerModesRequest(null, null, null, null, null, null, null, null, TimerMode.AUTOMATIC, null, null)));
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        service.chooseClue(as(ana), CLUE_ID);
        // Through the clue, Final Jeopardy's title card, wagering.
        for (int step = 0; step < 6; step++) {
            service.advance(as(trebek));
        }
        clearInvocations(timer);

        service.advance(as(trebek));

        // Each player's 3 steps share the default 10 s.
        assertEquals(GameSessionState.FINAL_JEOPARDY_REVEALING, session.getState());
        Duration step = Duration.ofSeconds(10).dividedBy(3);
        Runnable firstStep = scheduled(step);

        firstStep.run();
        Runnable secondStep = scheduled(step);
        secondStep.run();
        Runnable award = scheduled(step);
        clearInvocations(timer);

        award.run();

        assertTrue(session.isRevealWaitingForRuling());
        assertNull(session.getPhaseDuration());
        verify(timer, never()).schedule(any(), any(), any());

        service.ruleFinal(as(trebek), session.getRevealOrder().getFirst(), false);

        assertFalse(session.isRevealWaitingForRuling());
        verify(timer).schedule(eq(session.getId()), eq(step), any());
    }

    @Test
    void trebekLeavingDuringTheGame_closesTheSession() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        PlayerPrincipal trebekPrincipal = as(trebek);

        service.leave(trebekPrincipal);

        assertEquals(GameSessionState.CLOSED, session.getState());
        assertEquals(SessionCloseReason.TREBEK_LEFT, session.getCloseReason());
        assertThrows(GameSessionNotFoundException.class, () -> service.getSession(session.getId()));
        assertTrue(playerTokens.find(trebek.getToken()).isEmpty());
        assertTrue(playerTokens.find(ana.getToken()).isEmpty());
    }

    @Test
    void theLastContestantLeavingTheLobby_leavesTrebekWaiting() {
        service.leave(as(ana));
        service.leave(as(ben));

        assertSame(session, service.getSession(session.getId()));
        assertEquals(GameSessionState.WAITING_FOR_START, session.getState());
    }

    @Test
    void theLastContestantLeavingTheGame_closesIt() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        service.leave(as(ben));
        service.leave(as(ana));

        assertEquals(GameSessionState.CLOSED, session.getState());
        assertEquals(SessionCloseReason.ABANDONED, session.getCloseReason());
    }

    @Test
    void trebekSubscribing_getsTheirViewAtOnce_othersDont() {
        service.onTrebekViewRequested(new TrebekSubscriptions.TrebekViewRequested(as(ana)));
        verify(broadcaster, never()).sendTrebekView(any());

        service.onTrebekViewRequested(new TrebekSubscriptions.TrebekViewRequested(as(trebek)));
        verify(broadcaster).sendTrebekView(session);
    }

    @Test
    void everyoneAway_includesTrebek() {
        service.startGame(as(trebek));
        service.finishRoundIntro(session.getId());
        session.setAway(ana.getId(), true);
        session.setAway(ben.getId(), true);
        session.setAway(trebek.getId(), false);
        clearInvocations(timer);

        service.adjustScore(as(trebek), ana.getId(), 100);

        verify(timer, never()).scheduleAbandonment(any(), any(), any());
        assertEquals(100, ana.getScore());

        session.setAway(trebek.getId(), true);
        service.adjustScore(as(trebek), ana.getId(), 100);

        verify(timer).scheduleAbandonment(eq(session.getId()), eq(GameSessionService.EVERYONE_AWAY_TIMEOUT), any());
    }

    private PlayerPrincipal as(GameSessionPlayer player) {
        return PlayerPrincipal.of(session, player.getId());
    }

    // The last timer armed with this delay.
    private Runnable scheduled(Duration delay) {
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(timer, atLeastOnce()).schedule(eq(session.getId()), eq(delay), runnable.capture());
        return runnable.getValue();
    }
}
