package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import com.nickneim.jeopardy.session.model.FinalRevealStep;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.model.HostMode;
import com.nickneim.jeopardy.session.model.SessionCode;
import com.nickneim.jeopardy.session.model.TimerMode;
import com.nickneim.jeopardy.session.security.PlayerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a Trebek session shows everyone (the public snapshot, which must
 * never hold a secret) and what only Trebek sees (TrebekViewResponse).
 */
class TrebekResponsesTest {

    private static final Long TREBEK = 1L;
    private static final Long ANA = 2L;
    private static final Long BEN = 3L;

    // Distinctive texts, so a leak shows up in the JSON.
    private static final String QUESTION = "Secret question about the Danube";
    private static final String ANSWER = "Bratislava";
    private static final String DD_QUESTION = "Secret Daily Double question";
    private static final String DD_ANSWER = "Ljubljana";
    private static final String SUBMITTED = "Pressburg perhaps";
    private static final String FINAL_ANSWER = "Reykjavik";
    private static final String FINAL_SUBMITTED = "Oslo for sure";

    private final BoardClue capital = new BoardClue(101L, 1, QUESTION, ANSWER, 200, false);
    private final BoardClue dailyDouble = new BoardClue(102L, 2, DD_QUESTION, DD_ANSWER, 400, true);
    private final BoardClue finalClue = new BoardClue(301L, 1, "Northernmost capital", FINAL_ANSWER, 2000, false);

    private GameSession session;
    private GameSessionPlayer ana;
    private GameSessionPlayer ben;

    @BeforeEach
    void setUp() {
        session = new GameSession(SessionCode.random(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("CAPITALS", List.of(capital, dailyDouble))
                )),
                new BoardRound(GameRound.FINAL_JEOPARDY, List.of(
                        new BoardCategory("NORTH", List.of(finalClue))
                ))
        )));
        session.addPlayer(new GameSessionPlayer(TREBEK, "Alex", PlayerTokens.generate()));
        ana = new GameSessionPlayer(ANA, "Ana", PlayerTokens.generate());
        ben = new GameSessionPlayer(BEN, "Ben", PlayerTokens.generate());
        session.addPlayer(ana);
        session.addPlayer(ben);
        session.updateHostMode(TREBEK, HostMode.TREBEK);
    }

    @Test
    void contestantSession_showsEverythingAsBefore() {
        GameSession classic = new GameSession(SessionCode.random(), session.getBoard());
        classic.addPlayer(new GameSessionPlayer(TREBEK, "Alex", PlayerTokens.generate()));

        GameSessionResponse response = GameSessionResponse.from(classic);

        assertEquals(HostMode.CONTESTANT, response.hostMode());
        assertNull(response.trebek());
        assertFalse(response.paused());
        assertEquals(TimerMode.TREBEK, response.timerModes().clueRevealMs());
        assertTrue(toJson(response).contains("\"hostMode\":\"CONTESTANT\",\"trebek\":null"));
    }

    @Test
    void trebek_isShownApartFromThePlayers_withoutTheToken() {
        GameSessionResponse response = GameSessionResponse.from(session);

        assertEquals(new TrebekResponse(TREBEK, "Alex", false), response.trebek());
        assertEquals(TREBEK, response.hostPlayerId());
        assertEquals(List.of(ANA, BEN), response.players().stream().map(PlayerResponse::id).toList());
        assertFalse(toJson(response).contains(session.getTrebek().getToken()), "leaked Trebek's token");
    }

    @Test
    void pickedClue_showsOnlyWhichClueItIs() {
        session.startGame(TREBEK);
        session.finishRoundIntro();
        session.chooseClue(ANA, dailyDouble.id());

        GameSessionResponse response = GameSessionResponse.from(session);
        CurrentClueResponse clue = response.currentClue();

        assertEquals(GameSessionState.CLUE_PICKED, response.state());
        assertEquals(new CurrentClueResponse(dailyDouble.id(), 400, null, null, null, null, null, null, null, null, null, null), clue);
        assertTrue(toJson(response).contains("\"dailyDouble\":null"));
        assertNoClueSecrets(toJson(response));

        TrebekClueResponse trebekClue = TrebekViewResponse.from(session).clue();
        assertEquals(DD_QUESTION, trebekClue.question());
        assertEquals(DD_ANSWER, trebekClue.answer());
        assertTrue(trebekClue.dailyDouble());
        assertEquals("CAPITALS", trebekClue.categoryName());
        assertEquals(ClueType.TEXT, trebekClue.clueType());
        assertEquals(List.of(DD_ANSWER), trebekClue.acceptedAnswers().options());
    }

    @Test
    void answerWaitingForARuling_isOnlyInTrebeksView() {
        session.startGame(TREBEK);
        session.finishRoundIntro();
        session.chooseClue(ANA, capital.id());
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.buzz(BEN);
        session.answerClue(BEN, SUBMITTED);

        GameSessionResponse response = GameSessionResponse.from(session);
        String json = toJson(response);

        assertEquals(GameSessionState.WAITING_FOR_RULING, response.state());
        assertEquals(BEN, response.playerAnswering());
        assertEquals(List.of(), response.clueAnswers());
        assertFalse(json.contains(SUBMITTED), "leaked the pending answer: " + json);
        assertFalse(json.contains("\"" + ANSWER + "\""), "leaked the correct response: " + json);

        TrebekViewResponse view = TrebekViewResponse.from(session);
        assertEquals(response.version(), view.version());
        assertEquals(
                new TrebekPendingAnswerResponse(BEN, SUBMITTED, false, false, null),
                view.pendingAnswer()
        );
        assertEquals(ANSWER, view.clue().answer());
        assertNull(view.finalJeopardy());
    }

    @Test
    void ruledAnswer_isPublicWithTheRuling() {
        session.startGame(TREBEK);
        session.finishRoundIntro();
        session.chooseClue(ANA, capital.id());
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.buzz(BEN);
        session.answerClue(BEN, ANSWER);
        session.rule(TREBEK, true);

        GameSessionResponse response = GameSessionResponse.from(session);

        assertEquals(
                List.of(new ClueAnswerResponse(BEN, ANSWER, true, false, List.of(ANSWER), List.of(), true)),
                response.clueAnswers()
        );
        assertNull(TrebekViewResponse.from(session).pendingAnswer());
    }

    @Test
    void finalJeopardy_wagersAndAnswers_areOnlyInTrebeksView_untilTheirStep() {
        playToFinalJeopardy();
        session.placeFinalJeopardyWager(ANA, 123);
        assertNoFinalSecrets();
        assertEquals(123, playerView(ANA).wager());
        assertNull(playerView(BEN).wager(), "hasn't wagered yet");

        session.advance(TREBEK);
        session.answerFinalJeopardy(ANA, FINAL_SUBMITTED);
        assertNoFinalSecrets();
        assertEquals(FINAL_SUBMITTED, playerView(ANA).answer());
        assertEquals(false, playerView(ANA).suggestedCorrect());
        assertEquals(0, playerView(BEN).wager(), "a missing wager is 0 once wagering closes");
        assertNull(playerView(BEN).suggestedCorrect(), "no answer yet");

        session.advance(TREBEK);
        FinalJeopardyResponse finalJeopardy = GameSessionResponse.from(session).finalJeopardy();
        List<Long> order = finalJeopardy.revealOrder();
        Long first = order.getFirst();
        assertEquals(List.of(FinalRevealStep.HIDDEN, FinalRevealStep.HIDDEN),
                finalJeopardy.players().stream().map(FinalJeopardyPlayerResponse::revealStep).toList());
        assertTrue(finalJeopardy.players().stream().allMatch(player -> player.wager() == null && player.answer() == null));
        assertFalse(toJson(GameSessionResponse.from(session)).contains(FINAL_SUBMITTED));
        assertNull(playerView(BEN).ruling());

        session.advance(TREBEK);
        FinalJeopardyPlayerResponse wagerShown = entry(first);
        assertNotNull(wagerShown.wager());
        assertNull(wagerShown.answer());
        assertEquals(FinalRevealStep.WAGER, wagerShown.revealStep());

        session.advance(TREBEK);
        FinalJeopardyPlayerResponse answerShown = entry(first);
        assertNull(answerShown.correct());
        assertEquals(FinalRevealStep.ANSWER, answerShown.revealStep());

        session.ruleFinal(TREBEK, first, true);
        assertEquals(true, playerView(first).ruling());
        assertNull(entry(first).correct(), "the ruling is shown with the award");

        session.advance(TREBEK);
        assertEquals(true, entry(first).correct());
        assertEquals(FinalRevealStep.AWARDED, entry(first).revealStep());
    }

    @Test
    void summary_marksATrebekSession() {
        GameSessionSummaryResponse summary = GameSessionSummaryResponse.from(session);

        assertEquals(HostMode.TREBEK, summary.hostMode());
        assertEquals("Alex", summary.hostName());
        assertEquals(2, summary.playerCount(), "Trebek isn't counted");
    }

    // Ana answers the first clue (200), then her Daily Double wrong (the
    // minimum wager, 5): Ana 195, Ben 0, and Final Jeopardy starts.
    private void playToFinalJeopardy() {
        session.startGame(TREBEK);
        session.finishRoundIntro();
        session.chooseClue(ANA, capital.id());
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.buzz(ANA);
        session.answerClue(ANA, ANSWER);
        session.rule(TREBEK, true);
        session.advance(TREBEK);
        session.chooseClue(ANA, dailyDouble.id());
        // Shown, announced, wagered (the minimum), revealed.
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.advance(TREBEK);
        session.rule(TREBEK, false);
        // The answer reveal, then Final Jeopardy's title card.
        session.advance(TREBEK);
        session.advance(TREBEK);

        assertEquals(GameSessionState.FINAL_JEOPARDY_WAGERING, session.getState());
    }

    private void assertNoClueSecrets(String json) {
        assertFalse(json.contains(DD_QUESTION), "leaked the question: " + json);
        assertFalse(json.contains(DD_ANSWER), "leaked the answer: " + json);
        assertFalse(json.contains(QUESTION), "leaked a question: " + json);
    }

    private void assertNoFinalSecrets() {
        String json = toJson(GameSessionResponse.from(session));

        assertFalse(json.contains(FINAL_SUBMITTED), "leaked an answer: " + json);
        assertFalse(json.contains(FINAL_ANSWER), "leaked the correct response: " + json);
        assertTrue(
                GameSessionResponse.from(session).finalJeopardy().players().stream().allMatch(player -> player.wager() == null),
                "leaked a wager: " + json
        );
    }

    private TrebekFinalJeopardyResponse.Player playerView(Long playerId) {
        return TrebekViewResponse.from(session).finalJeopardy().players().stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private FinalJeopardyPlayerResponse entry(Long playerId) {
        return GameSessionResponse.from(session).finalJeopardy().players().stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private static String toJson(Object response) {
        return JsonMapper.builder().build().writeValueAsString(response);
    }
}
