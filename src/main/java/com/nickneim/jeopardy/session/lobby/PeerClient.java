package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;

import java.util.List;

/** Asks one instance, by address, for its sessions ({@link GameSessionsEndpoint}). */
interface PeerClient {

    List<GameSessionSummaryResponse> fetch(String address) throws Exception;
}
