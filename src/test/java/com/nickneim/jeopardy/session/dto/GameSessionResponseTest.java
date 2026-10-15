package com.nickneim.jeopardy.session.dto;

import com.nickneim.jeopardy.game.entity.ClueType;
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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionResponseTest {

    private final BoardClue historyRow1 =
            new BoardClue(101L, 1, "First president?", "George Washington", 200, false);
    private final BoardClue historyRow3 =
            new BoardClue(103L, 3, "Second president?", "John Adams", 600, true);
    private final BoardClue scienceRow1 =
            new BoardClue(201L, 1, "Symbol for gold?", "Au", 200, false);
    private final BoardClue scienceRow2 =
            new BoardClue(202L, 2, "Red planet?", "Mars", 400, false);
    private final BoardClue doubleJeopardyClue =
            new BoardClue(301L, 1, "He wrote Hamlet.", "William Shakespeare", 400, false);

    private GameSession session;
    private GameSessionPlayer player;

    @BeforeEach
    void setUp() {
        GameBoard board = new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("HISTORY", "(Alex: U.S. presidents.)", List.of(historyRow1, historyRow3)),
                        new BoardCategory("SCIENCE", List.of(scienceRow1, scienceRow2))
                )),
                new BoardRound(GameRound.DOUBLE_JEOPARDY, List.of(
                        new BoardCategory("LITERATURE", List.of(doubleJeopardyClue))
                ))
        ));

        session = new GameSession(UUID.randomUUID(), board);
        player = new GameSessionPlayer(1L, "Nico");
        session.addPlayer(player);
    }

    @Test
    void board_showsCurrentRoundAsRectangularGrid() {
        session.startGame(player.getId());

        List<BoardCategoryResponse> board = GameSessionResponse.from(session).board();

        assertEquals(
                List.of("HISTORY", "SCIENCE"),
                board.stream().map(BoardCategoryResponse::name).toList()
        );

        // Highest row in the round is 3, so every category has 3 slots.
        assertEquals(
                List.of(
                        new ClueSlotResponse(1, 101L, 200, ClueSlotStatus.AVAILABLE),
                        ClueSlotResponse.empty(2),
                        new ClueSlotResponse(3, 103L, 600, ClueSlotStatus.AVAILABLE)
                ),
                board.get(0).slots()
        );
        assertEquals(
                List.of(
                        new ClueSlotResponse(1, 201L, 200, ClueSlotStatus.AVAILABLE),
                        new ClueSlotResponse(2, 202L, 400, ClueSlotStatus.AVAILABLE),
                        ClueSlotResponse.empty(3)
                ),
                board.get(1).slots()
        );
    }

    @Test
    void board_includesCategoryComments() {
        session.startGame(player.getId());

        List<BoardCategoryResponse> board = GameSessionResponse.from(session).board();

        assertEquals("(Alex: U.S. presidents.)", board.get(0).comments());
        assertNull(board.get(1).comments());
        assertTrue(
                toJson(GameSessionResponse.from(session))
                        .contains("\"comments\":\"(Alex: U.S. presidents.)\""),
                "comments missing from the JSON"
        );
    }

    @Test
    void board_marksChosenCluesAsPlayed() {
        session.startGame(player.getId());
        session.chooseClue(player.getId(), scienceRow2.id());

        List<BoardCategoryResponse> board = GameSessionResponse.from(session).board();

        assertEquals(
                ClueSlotStatus.PLAYED,
                board.get(1).slots().get(1).status()
        );
        assertEquals(
                ClueSlotStatus.AVAILABLE,
                board.get(1).slots().get(0).status()
        );
    }

    @Test
    void board_followsTheCurrentRound() {
        session.startGame(player.getId());

        for (BoardClue clue : List.of(historyRow1, scienceRow1, scienceRow2)) {
            session.chooseClue(player.getId(), clue.id());
            session.finishRevealingClue();
            session.timeoutBuzzer();
            session.finishRevealingAnswer();
        }

        session.chooseClue(player.getId(), historyRow3.id());
        session.placeDailyDoubleWager(player.getId(), 5);
        session.finishRevealingClue();
        session.answerClue(player.getId(), "John Adams");
        session.finishRevealingAnswer();

        GameSessionResponse response = GameSessionResponse.from(session);

        assertEquals(GameRound.DOUBLE_JEOPARDY, response.round());
        assertEquals(
                List.of(new BoardCategoryResponse(
                        "LITERATURE",
                        null,
                        List.of(new ClueSlotResponse(1, 301L, 400, ClueSlotStatus.AVAILABLE))
                )),
                response.board()
        );
    }

    @Test
    void board_isShownBeforeTheGameStarts() {
        GameSessionResponse response = GameSessionResponse.from(session);

        assertEquals(GameRound.JEOPARDY, response.round());
        assertEquals(2, response.board().size());
    }

    @Test
    void serializedResponse_neverContainsQuestionsAnswersOrDailyDoubles() {
        session.startGame(player.getId());

        String json = JsonMapper.builder().build()
                .writeValueAsString(GameSessionResponse.from(session));

        // Guard against a vacuous pass: the board itself must be in the JSON.
        assertTrue(json.contains("\"HISTORY\""), json);
        assertTrue(json.contains("\"AVAILABLE\""), json);

        for (BoardClue clue : List.of(historyRow1, historyRow3, scienceRow1, scienceRow2)) {
            assertFalse(json.contains(clue.question()), "leaked question: " + clue.question());
            assertFalse(json.contains(clue.answer()), "leaked answer: " + clue.answer());
        }
        // timers.dailyDoubleWagerMs is a public setting, not a flag.
        String withoutTimers = json.replace("\"dailyDoubleWagerMs\"", "");
        assertFalse(withoutTimers.toLowerCase().contains("dailydouble"), "leaked Daily Double flag: " + json);
    }

    // -------------------------------------------------------------------------
    // Current clue
    // -------------------------------------------------------------------------

    @Test
    void currentClue_isNullWhenNoClueIsBeingPlayed() {
        assertNull(GameSessionResponse.from(session).currentClue());

        session.startGame(player.getId());
        assertNull(GameSessionResponse.from(session).currentClue());

        session.chooseClue(player.getId(), scienceRow1.id());
        session.finishRevealingClue();
        session.timeoutBuzzer();
        session.finishRevealingAnswer();

        assertNull(GameSessionResponse.from(session).currentClue());
    }

    @Test
    void currentClue_showsQuestionButNotAnswerWhileClueIsInPlay() {
        session.startGame(player.getId());

        CurrentClueResponse expected = new CurrentClueResponse(
                scienceRow1.id(),
                200,
                false,
                null,
                "Symbol for gold?",
                ClueType.TEXT,
                null,
                false,
                null
        );

        session.chooseClue(player.getId(), scienceRow1.id());
        assertEquals(expected, GameSessionResponse.from(session).currentClue());

        session.finishRevealingClue();
        assertEquals(expected, GameSessionResponse.from(session).currentClue());

        session.buzz(player.getId());
        assertEquals(expected, GameSessionResponse.from(session).currentClue());
    }

    @Test
    void currentClue_showsAnswerWhenRevealingAnswer() {
        session.startGame(player.getId());
        session.chooseClue(player.getId(), scienceRow1.id());
        session.finishRevealingClue();
        session.timeoutBuzzer();

        assertEquals(
                new CurrentClueResponse(scienceRow1.id(), 200, false, null, "Symbol for gold?", ClueType.TEXT, null, false, "Au"),
                GameSessionResponse.from(session).currentClue()
        );
    }

    @Test
    void currentClue_hidesDailyDoubleQuestionUntilWagerIsPlaced() {
        session.startGame(player.getId());
        session.chooseClue(player.getId(), historyRow3.id());

        assertEquals(
                new CurrentClueResponse(historyRow3.id(), 600, true, null, null, null, null, null, null),
                GameSessionResponse.from(session).currentClue()
        );

        // Also pins the JSON shape the leak checks rely on.
        assertTrue(
                JsonMapper.builder().build()
                        .writeValueAsString(GameSessionResponse.from(session))
                        .contains("\"dailyDouble\":true")
        );

        session.placeDailyDoubleWager(player.getId(), 500);

        assertEquals(
                new CurrentClueResponse(historyRow3.id(), 600, true, 500, "Second president?", ClueType.TEXT, null, false, null),
                GameSessionResponse.from(session).currentClue()
        );
    }

    @Test
    void currentClue_saysWhenOnlyAnExactNumberIsAccepted() {
        BoardClue numberClue = new BoardClue(401L, 1, "Feet in a mile", "5,280", 200, false);
        GameSession numberSession = new GameSession(UUID.randomUUID(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(new BoardCategory("NUMBERS", List.of(numberClue))))
        )));
        numberSession.addPlayer(player);
        numberSession.startGame(player.getId());
        numberSession.chooseClue(player.getId(), numberClue.id());

        assertEquals(true, GameSessionResponse.from(numberSession).currentClue().numberExpected());
    }

    @Test
    void serializedResponse_whileAnswering_onlyContainsTheCurrentQuestion() {
        session.startGame(player.getId());
        session.chooseClue(player.getId(), scienceRow1.id());
        session.finishRevealingClue();
        session.buzz(player.getId());

        String json = JsonMapper.builder().build()
                .writeValueAsString(GameSessionResponse.from(session));

        assertTrue(json.contains(scienceRow1.question()), json);
        assertFalse(json.contains(scienceRow1.answer()), "leaked current answer: " + json);

        for (BoardClue clue : List.of(historyRow1, historyRow3, scienceRow2)) {
            assertFalse(json.contains(clue.question()), "leaked question: " + clue.question());
            assertFalse(json.contains(clue.answer()), "leaked answer: " + clue.answer());
        }
        assertFalse(json.contains("\"dailyDouble\":true"), "leaked a Daily Double: " + json);
    }

    @Test
    void currentClue_hidesMediaOfADailyDoubleUntilTheWagerIsPlaced() {
        BoardClue imageDailyDouble = new BoardClue(
                401L, 1, "This painting hangs in the Louvre.", "Mona Lisa", 1000, true,
                ClueType.IMAGE, "https://media.example/secret-painting.jpg"
        );
        GameSession mediaSession = new GameSession(UUID.randomUUID(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("ART", List.of(imageDailyDouble))
                ))
        )));
        mediaSession.addPlayer(player);
        mediaSession.startGame(player.getId());
        mediaSession.chooseClue(player.getId(), imageDailyDouble.id());

        GameSessionResponse wagering = GameSessionResponse.from(mediaSession);
        assertNull(wagering.currentClue().clueType());
        assertNull(wagering.currentClue().mediaUrl());

        String json = toJson(wagering);
        assertFalse(json.contains("secret-painting"), "leaked the media URL: " + json);
        assertFalse(json.contains("IMAGE"), "leaked the clue type: " + json);

        mediaSession.placeDailyDoubleWager(player.getId(), 500);

        CurrentClueResponse revealed = GameSessionResponse.from(mediaSession).currentClue();
        assertEquals(ClueType.IMAGE, revealed.clueType());
        assertEquals("https://media.example/secret-painting.jpg", revealed.mediaUrl());
        assertNull(revealed.answer());
    }

    @Test
    void currentClue_showsMediaFromTheClueReveal() {
        BoardClue soundClue = new BoardClue(
                402L, 1, "Name this tune.", "Happy Birthday", 200, false,
                ClueType.SOUND, "https://media.example/tune.mp3"
        );
        GameSession mediaSession = new GameSession(UUID.randomUUID(), new GameBoard(List.of(
                new BoardRound(GameRound.JEOPARDY, List.of(
                        new BoardCategory("MUSIC", List.of(soundClue))
                ))
        )));
        mediaSession.addPlayer(player);
        mediaSession.startGame(player.getId());
        mediaSession.chooseClue(player.getId(), soundClue.id());

        CurrentClueResponse revealing = GameSessionResponse.from(mediaSession).currentClue();
        assertEquals(ClueType.SOUND, revealing.clueType());
        assertEquals("https://media.example/tune.mp3", revealing.mediaUrl());
    }

    // -------------------------------------------------------------------------
    // Phase timing
    // -------------------------------------------------------------------------

    @Test
    void phaseTiming_isNullWithoutATimer() {
        GameSessionResponse response = GameSessionResponse.from(session);

        assertNull(response.phaseDurationMs());
        assertNull(response.phaseRemainingMs());

        String json = toJson(response);
        assertTrue(json.contains("\"phaseDurationMs\":null"), json);
        assertTrue(json.contains("\"phaseRemainingMs\":null"), json);
    }

    @Test
    void phaseTiming_isMeasuredWhenTheResponseIsBuilt() {
        session.startGame(player.getId());
        session.startPhaseTimer(Duration.ofSeconds(30), 0);

        GameSessionResponse atStart = GameSessionResponse.from(session, 0);
        assertEquals(30_000L, atStart.phaseDurationMs());
        assertEquals(30_000L, atStart.phaseRemainingMs());

        // A client fetching the state later gets the same duration but less time.
        GameSessionResponse later = GameSessionResponse.from(session, 12_345_000_000L);
        assertEquals(30_000L, later.phaseDurationMs());
        assertEquals(17_655L, later.phaseRemainingMs());
    }

    // -------------------------------------------------------------------------
    // Answer results
    // -------------------------------------------------------------------------

    @Test
    void clueAnswers_isAnEmptyListBeforeAnyAnswer() {
        session.startGame(player.getId());

        assertEquals(List.of(), GameSessionResponse.from(session).clueAnswers());
        assertTrue(toJson(GameSessionResponse.from(session)).contains("\"clueAnswers\":[]"));
    }

    @Test
    void serializedResponse_afterWrongAnswer_showsWhatWasAnsweredButNotTheCorrectResponse() {
        GameSessionPlayer secondPlayer = new GameSessionPlayer(2L, "John");
        session.addPlayer(secondPlayer);

        session.startGame(player.getId());
        session.chooseClue(player.getId(), scienceRow1.id());
        session.finishRevealingClue();
        session.buzz(player.getId());
        session.answerClue(player.getId(), "Silver");

        GameSessionResponse response = GameSessionResponse.from(session);
        assertEquals(GameSessionState.WAITING_FOR_BUZZER, response.state());
        assertEquals(
                List.of(new ClueAnswerResponse(player.getId(), "Silver", false)),
                response.clueAnswers()
        );

        String json = toJson(response);
        assertTrue(
                json.contains("{\"playerId\":1,\"answer\":\"Silver\",\"correct\":false}"),
                json
        );
        assertFalse(json.contains("\"" + scienceRow1.answer() + "\""), "leaked the correct response: " + json);
    }

    @Test
    void serializedResponse_afterCorrectAnswer_onlyShowsItDuringTheReveal() {
        session.startGame(player.getId());
        session.chooseClue(player.getId(), scienceRow1.id());
        session.finishRevealingClue();
        session.buzz(player.getId());
        session.answerClue(player.getId(), "Au");

        GameSessionResponse response = GameSessionResponse.from(session);

        // A correct answer always ends the clue, so the correct response it
        // contains is public by now anyway.
        assertEquals(GameSessionState.REVEALING_ANSWER, response.state());
        assertEquals(
                List.of(new ClueAnswerResponse(player.getId(), "Au", true)),
                response.clueAnswers()
        );
        assertEquals("Au", response.currentClue().answer());
    }

    // -------------------------------------------------------------------------
    // Turn and buzzer
    // -------------------------------------------------------------------------

    @Test
    void response_includesTurnAndBuzzerInfo() {
        GameSessionResponse beforeStart = GameSessionResponse.from(session);
        assertNull(beforeStart.playerChoosingClue());
        assertNull(beforeStart.playerAnswering());
        assertEquals(Set.of(), beforeStart.playersLockedOut());

        session.startGame(player.getId());
        session.chooseClue(player.getId(), scienceRow1.id());
        session.finishRevealingClue();
        session.buzz(player.getId());

        GameSessionResponse answering = GameSessionResponse.from(session);
        assertEquals(player.getId(), answering.playerChoosingClue());
        assertEquals(player.getId(), answering.playerAnswering());
        assertEquals(Set.of(player.getId()), answering.playersLockedOut());
    }

    @Test
    void serializedResponse_neverContainsAPlayersToken() {
        session.startGame(player.getId());

        String json = JsonMapper.builder().build()
                .writeValueAsString(GameSessionResponse.from(session));

        // Guard against a vacuous pass: the player is in the JSON.
        assertTrue(json.contains("\"Nico\""), json);
        assertFalse(json.contains(player.getToken()), "leaked a player token: " + json);
    }

    @Test
    void serializedSummary_neverContainsTokens() {
        String json = JsonMapper.builder().build()
                .writeValueAsString(GameSessionSummaryResponse.from(session));

        // Guard against a vacuous pass: the host's name is in the JSON.
        assertTrue(json.contains("\"Nico\""), json);
        assertFalse(json.contains(player.getToken()), "leaked a player token: " + json);
    }

    private String toJson(GameSessionResponse response) {
        return JsonMapper.builder().build().writeValueAsString(response);
    }
}
