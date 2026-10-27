package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.dto.SortDirection;
import com.nickneim.jeopardy.exception.InvalidParameterException;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionLobbyTest {

    private GameSessionService gameSessionService;
    private PeerSessions peerSessions;
    private SessionLobby lobby;

    @BeforeEach
    void setUp() {
        gameSessionService = mock(GameSessionService.class);
        peerSessions = mock(PeerSessions.class);
        lobby = new SessionLobby(gameSessionService, peerSessions);

        when(gameSessionService.localSummaries()).thenReturn(List.of());
        when(peerSessions.fetch()).thenReturn(List.of());
    }

    @Test
    void listsThisInstancesAndTheOthersSessions_newestFirst_withoutFinishedOnesByDefault() {
        GameSessionSummaryResponse localOld = summary("10:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse localFinished = summary("11:00", GameSessionState.FINISHED_GAME);
        GameSessionSummaryResponse peerNew = summary("12:00", GameSessionState.CHOOSING_CLUE);
        GameSessionSummaryResponse peerMiddle = summary("10:30", GameSessionState.WAITING_FOR_START);
        when(gameSessionService.localSummaries()).thenReturn(List.of(localOld, localFinished));
        when(peerSessions.fetch()).thenReturn(List.of(peerMiddle, peerNew));

        assertEquals(
                List.of(peerNew, peerMiddle, localOld),
                lobby.listSessions(SessionFilter.NONE, SessionSort.DEFAULT, 0, 20).content()
        );
        assertEquals(
                List.of(peerNew, localFinished, peerMiddle, localOld),
                lobby.listSessions(new SessionFilter(null, null, false, true), SessionSort.DEFAULT, 0, 20).content()
        );
    }

    @Test
    void isPagedAcrossInstances() {
        GameSessionSummaryResponse oldest = summary("10:00", GameSessionState.WAITING_FOR_START);
        when(gameSessionService.localSummaries()).thenReturn(List.of(
                oldest,
                summary("12:00", GameSessionState.WAITING_FOR_START)
        ));
        when(peerSessions.fetch()).thenReturn(List.of(
                summary("11:00", GameSessionState.WAITING_FOR_START)
        ));

        PageResponse<GameSessionSummaryResponse> page = lobby.listSessions(SessionFilter.NONE, SessionSort.DEFAULT, 1, 2);

        assertEquals(List.of(oldest), page.content());
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(3, page.totalElements());
        assertEquals(2, page.totalPages());
        assertEquals(List.of(), lobby.listSessions(SessionFilter.NONE, SessionSort.DEFAULT, 5, 2).content());
    }

    // The controller validates paging too; this guards other callers.
    @Test
    void rejectsInvalidPagingBeforeAskingThePeers() {
        assertThrows(IllegalArgumentException.class, () -> lobby.listSessions(SessionFilter.NONE, SessionSort.DEFAULT, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> lobby.listSessions(SessionFilter.NONE, SessionSort.DEFAULT, -1, 20));
        verifyNoInteractions(peerSessions);
    }

    // Without its own address configured, an instance also gets its sessions
    // back from asking itself.
    @Test
    void listsASessionOnce_keepingTheSummaryReadDirectly() {
        GameSessionSummaryResponse local = summary("10:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse sameFromPeer = new GameSessionSummaryResponse(
                local.id(), local.gameId(), local.gameTitle(), local.gameSeason(), local.gameDate(),
                local.state(), local.round(),
                local.playerCount() + 1, local.hostName(), local.createdAt(),
                local.joinable(), local.allowLateJoin(), local.joinScore(), local.custom(), local.clueCount()
        );
        when(gameSessionService.localSummaries()).thenReturn(List.of(local));
        when(peerSessions.fetch()).thenReturn(List.of(sameFromPeer));

        List<GameSessionSummaryResponse> listed = lobby.listSessions(SessionFilter.NONE, SessionSort.DEFAULT, 0, 20).content();

        assertEquals(1, listed.size());
        assertSame(local, listed.getFirst());
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    @Test
    void search_matchesTheGameTitleOrHostName_caseInsensitively() {
        GameSessionSummaryResponse byTitle = withGame(summary("10:00", GameSessionState.WAITING_FOR_START), 1L, "Ken Jennings Special", "jm", null);
        GameSessionSummaryResponse byHost = withHost(summary("11:00", GameSessionState.WAITING_FOR_START), "Jenny");
        GameSessionSummaryResponse neither = summary("12:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse custom = withHost(withGame(summary("13:00", GameSessionState.WAITING_FOR_START), null, null, null, null), null);
        when(gameSessionService.localSummaries()).thenReturn(List.of(byTitle, byHost, neither, custom));

        assertEquals(List.of(byHost, byTitle), list(new SessionFilter("  JENN ", null, false, false)));
        // Blank means no filter.
        assertEquals(4, list(new SessionFilter("  ", null, false, false)).size());
    }

    @Test
    void stage_matchesTheLobbyARoundInProgressOrFinishedGames() {
        GameSessionSummaryResponse lobbySession = summary("10:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse jeopardy = summary("11:00", GameSessionState.CHOOSING_CLUE);
        GameSessionSummaryResponse doubleJeopardy = withRound(summary("12:00", GameSessionState.WAITING_FOR_BUZZER), GameRound.DOUBLE_JEOPARDY);
        GameSessionSummaryResponse finalJeopardy = withRound(summary("13:00", GameSessionState.FINAL_JEOPARDY_WAGERING), GameRound.FINAL_JEOPARDY);
        GameSessionSummaryResponse finished = withRound(summary("14:00", GameSessionState.FINISHED_GAME), GameRound.FINAL_JEOPARDY);
        when(gameSessionService.localSummaries())
                .thenReturn(List.of(lobbySession, jeopardy, doubleJeopardy, finalJeopardy, finished));

        assertEquals(List.of(lobbySession), list(new SessionFilter(null, SessionStage.LOBBY, false, false)));
        assertEquals(List.of(jeopardy), list(new SessionFilter(null, SessionStage.JEOPARDY, false, false)));
        assertEquals(List.of(doubleJeopardy), list(new SessionFilter(null, SessionStage.DOUBLE_JEOPARDY, false, false)));
        assertEquals(List.of(finalJeopardy), list(new SessionFilter(null, SessionStage.FINAL_JEOPARDY, false, false)));
        // FINISHED lists finished games without includeFinished.
        assertEquals(List.of(finished), list(new SessionFilter(null, SessionStage.FINISHED, false, false)));
    }

    @Test
    void joinable_listsOnlyJoinableSessions_andFiltersCombineBeforePaging() {
        GameSessionSummaryResponse joinableA = summary("10:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse notJoinable = summary("11:00", GameSessionState.CHOOSING_CLUE);
        GameSessionSummaryResponse joinableB = summary("12:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse otherHost = withHost(summary("13:00", GameSessionState.WAITING_FOR_START), "Ana");
        when(gameSessionService.localSummaries()).thenReturn(List.of(joinableA, notJoinable));
        when(peerSessions.fetch()).thenReturn(List.of(joinableB, otherHost));

        PageResponse<GameSessionSummaryResponse> page = lobby.listSessions(
                new SessionFilter("nico", null, true, false), SessionSort.DEFAULT, 0, 1);

        assertEquals(List.of(joinableB), page.content());
        assertEquals(2, page.totalElements());
        assertEquals(2, page.totalPages());
    }

    // -------------------------------------------------------------------------
    // Sorting
    // -------------------------------------------------------------------------

    @Test
    void sortsEveryInstancesSessionsTogether_tiesById() {
        GameSessionSummaryResponse a = withPlayerCount(summary("10:00", GameSessionState.WAITING_FOR_START), 3);
        GameSessionSummaryResponse b = withPlayerCount(summary("11:00", GameSessionState.WAITING_FOR_START), 1);
        GameSessionSummaryResponse c = withPlayerCount(summary("12:00", GameSessionState.WAITING_FOR_START), 3);
        when(gameSessionService.localSummaries()).thenReturn(List.of(a));
        when(peerSessions.fetch()).thenReturn(List.of(b, c));

        List<GameSessionSummaryResponse> threes = byId(a, c);

        assertEquals(
                List.of(b, threes.get(0), threes.get(1)),
                list(sort(SessionSort.Field.PLAYER_COUNT, SortDirection.ASC))
        );
        assertEquals(
                List.of(threes.get(1), threes.get(0), b),
                list(sort(SessionSort.Field.PLAYER_COUNT, SortDirection.DESC))
        );
    }

    @Test
    void sortsByStageInGameOrder() {
        GameSessionSummaryResponse finished = withRound(summary("10:00", GameSessionState.FINISHED_GAME), GameRound.FINAL_JEOPARDY);
        GameSessionSummaryResponse doubleJeopardy = withRound(summary("11:00", GameSessionState.CHOOSING_CLUE), GameRound.DOUBLE_JEOPARDY);
        GameSessionSummaryResponse lobbySession = summary("12:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse jeopardy = summary("13:00", GameSessionState.REVEALING_CLUE);
        when(gameSessionService.localSummaries()).thenReturn(List.of(finished, doubleJeopardy, lobbySession, jeopardy));

        assertEquals(
                List.of(lobbySession, jeopardy, doubleJeopardy, finished),
                lobby.listSessions(
                        new SessionFilter(null, null, false, true),
                        sort(SessionSort.Field.STAGE, SortDirection.ASC), 0, 20).content()
        );
    }

    // The same order as GET /api/games's title sort (GameSortIntegrationTest).
    @Test
    void sortsTitlesByCodePoint_likeTheGameList() {
        GameSessionSummaryResponse hundred = withGame(
                summary("10:00", GameSessionState.WAITING_FOR_START), 1L, "Show #100 - Tuesday", "1", null);
        GameSessionSummaryResponse ten = withGame(
                summary("11:00", GameSessionState.WAITING_FOR_START), 2L, "show #10 - Monday", "1", null);
        when(gameSessionService.localSummaries()).thenReturn(List.of(hundred, ten));

        assertEquals(List.of(ten, hundred), list(sort(SessionSort.Field.GAME_TITLE, SortDirection.ASC)));
    }

    @Test
    void sortsSeasonsLikeTheGameList_andMissingValuesLastInBothDirections() {
        GameSessionSummaryResponse nine = withGame(summary("10:00", GameSessionState.WAITING_FOR_START), 1L, "Show #1", "9", LocalDate.of(1993, 1, 1));
        GameSessionSummaryResponse ten = withGame(summary("11:00", GameSessionState.WAITING_FOR_START), 2L, "show #2", "10", LocalDate.of(1994, 1, 1));
        GameSessionSummaryResponse special = withGame(summary("12:00", GameSessionState.WAITING_FOR_START), 3L, "Show #3", "jm", LocalDate.of(2026, 1, 1));
        GameSessionSummaryResponse custom = withGame(summary("13:00", GameSessionState.WAITING_FOR_START), null, null, null, null);
        when(gameSessionService.localSummaries()).thenReturn(List.of(special, custom, ten, nine));

        assertEquals(List.of(nine, ten, special, custom), list(sort(SessionSort.Field.GAME_SEASON, SortDirection.ASC)));
        assertEquals(List.of(ten, nine, special, custom), list(sort(SessionSort.Field.GAME_SEASON, SortDirection.DESC)));
        assertEquals(List.of(nine, ten, special, custom), list(sort(SessionSort.Field.GAME_DATE, SortDirection.ASC)));
        assertEquals(List.of(special, ten, nine, custom), list(sort(SessionSort.Field.GAME_DATE, SortDirection.DESC)));
        // Case-insensitive: "show #2" between "Show #1" and "Show #3".
        assertEquals(List.of(nine, ten, special, custom), list(sort(SessionSort.Field.GAME_TITLE, SortDirection.ASC)));
        assertEquals(List.of(special, ten, nine, custom), list(sort(SessionSort.Field.GAME_TITLE, SortDirection.DESC)));
    }

    @Test
    void parsesTheSortAndDirection() {
        assertEquals(SessionSort.DEFAULT, SessionSort.parse(null, null));
        assertEquals(SessionSort.DEFAULT, SessionSort.parse("createdAt", null));
        assertEquals(sort(SessionSort.Field.ID, SortDirection.ASC), SessionSort.parse("id", null));
        assertEquals(sort(SessionSort.Field.HOST_NAME, SortDirection.DESC), SessionSort.parse("hostname", "DESC"));
        assertThrows(InvalidParameterException.class, () -> SessionSort.parse("score", null));
        assertThrows(InvalidParameterException.class, () -> SessionSort.parse(null, "up"));
        assertEquals(SessionStage.FINAL_JEOPARDY, SessionStage.parse("final_jeopardy"));
        assertThrows(InvalidParameterException.class, () -> SessionStage.parse("PLAYING"));
    }

    private List<GameSessionSummaryResponse> list(SessionFilter filter) {
        return lobby.listSessions(filter, SessionSort.DEFAULT, 0, 20).content();
    }

    private List<GameSessionSummaryResponse> list(SessionSort sort) {
        return lobby.listSessions(SessionFilter.NONE, sort, 0, 20).content();
    }

    private static SessionSort sort(SessionSort.Field field, SortDirection direction) {
        return new SessionSort(field, direction);
    }

    private static List<GameSessionSummaryResponse> byId(GameSessionSummaryResponse... summaries) {
        return Stream.of(summaries).sorted(Comparator.comparing(GameSessionSummaryResponse::id)).toList();
    }

    private static GameSessionSummaryResponse summary(String time, GameSessionState state) {
        return new GameSessionSummaryResponse(
                SessionCode.random(),
                735L,
                "Show #735",
                "6",
                LocalDate.of(1990, 1, 1),
                state,
                GameRound.JEOPARDY,
                1,
                "Nico",
                Instant.parse("2026-10-09T" + time + ":00Z"),
                state == GameSessionState.WAITING_FOR_START,
                true,
                0,
                false,
                61
        );
    }

    private static GameSessionSummaryResponse withGame(
            GameSessionSummaryResponse s, Long gameId, String title, String season, LocalDate date
    ) {
        return new GameSessionSummaryResponse(s.id(), gameId, title, season, date, s.state(), s.round(),
                s.playerCount(), s.hostName(), s.createdAt(), s.joinable(), s.allowLateJoin(), s.joinScore(), s.custom(), s.clueCount());
    }

    private static GameSessionSummaryResponse withHost(GameSessionSummaryResponse s, String hostName) {
        return new GameSessionSummaryResponse(s.id(), s.gameId(), s.gameTitle(), s.gameSeason(), s.gameDate(),
                s.state(), s.round(), s.playerCount(), hostName, s.createdAt(), s.joinable(), s.allowLateJoin(),
                s.joinScore(), s.custom(), s.clueCount());
    }

    private static GameSessionSummaryResponse withRound(GameSessionSummaryResponse s, GameRound round) {
        return new GameSessionSummaryResponse(s.id(), s.gameId(), s.gameTitle(), s.gameSeason(), s.gameDate(),
                s.state(), round, s.playerCount(), s.hostName(), s.createdAt(), s.joinable(), s.allowLateJoin(),
                s.joinScore(), s.custom(), s.clueCount());
    }

    private static GameSessionSummaryResponse withPlayerCount(GameSessionSummaryResponse s, int playerCount) {
        return new GameSessionSummaryResponse(s.id(), s.gameId(), s.gameTitle(), s.gameSeason(), s.gameDate(),
                s.state(), s.round(), playerCount, s.hostName(), s.createdAt(), s.joinable(), s.allowLateJoin(),
                s.joinScore(), s.custom(), s.clueCount());
    }
}
