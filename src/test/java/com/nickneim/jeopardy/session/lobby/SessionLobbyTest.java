package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;
import com.nickneim.jeopardy.session.service.GameSessionService;
import com.nickneim.jeopardy.session.model.SessionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
                lobby.listSessions(false, 0, 20).content()
        );
        assertEquals(
                List.of(peerNew, localFinished, peerMiddle, localOld),
                lobby.listSessions(true, 0, 20).content()
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

        PageResponse<GameSessionSummaryResponse> page = lobby.listSessions(false, 1, 2);

        assertEquals(List.of(oldest), page.content());
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(3, page.totalElements());
        assertEquals(2, page.totalPages());
        assertEquals(List.of(), lobby.listSessions(false, 5, 2).content());
    }

    // The controller validates paging too; this guards other callers.
    @Test
    void rejectsInvalidPagingBeforeAskingThePeers() {
        assertThrows(IllegalArgumentException.class, () -> lobby.listSessions(false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> lobby.listSessions(false, -1, 20));
        verifyNoInteractions(peerSessions);
    }

    // Without its own address configured, an instance also gets its sessions
    // back from asking itself.
    @Test
    void listsASessionOnce_keepingTheSummaryReadDirectly() {
        GameSessionSummaryResponse local = summary("10:00", GameSessionState.WAITING_FOR_START);
        GameSessionSummaryResponse sameFromPeer = new GameSessionSummaryResponse(
                local.id(), local.gameId(), local.gameTitle(), local.state(), local.round(),
                local.playerCount() + 1, local.hostName(), local.createdAt(),
                local.joinable(), local.allowLateJoin(), local.joinScore()
        );
        when(gameSessionService.localSummaries()).thenReturn(List.of(local));
        when(peerSessions.fetch()).thenReturn(List.of(sameFromPeer));

        List<GameSessionSummaryResponse> listed = lobby.listSessions(false, 0, 20).content();

        assertEquals(1, listed.size());
        assertSame(local, listed.getFirst());
    }

    private static GameSessionSummaryResponse summary(String time, GameSessionState state) {
        return new GameSessionSummaryResponse(
                SessionCode.random(),
                735L,
                "Show #735",
                state,
                GameRound.JEOPARDY,
                1,
                "Nico",
                Instant.parse("2026-10-09T" + time + ":00Z"),
                state == GameSessionState.WAITING_FOR_START,
                true,
                0
        );
    }
}
