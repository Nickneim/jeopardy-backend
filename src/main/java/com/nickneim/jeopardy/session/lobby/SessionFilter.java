package com.nickneim.jeopardy.session.lobby;

import com.nickneim.jeopardy.session.dto.GameSessionSummaryResponse;
import com.nickneim.jeopardy.session.model.GameSessionState;

import java.util.Locale;

import static com.nickneim.jeopardy.dto.QueryParameters.trimToNull;

/**
 * The filters of GET /api/game-sessions, all of which a session must match.
 * A null {@code search} or {@code stage} doesn't filter; {@code search} is
 * trimmed, and a blank one is treated as null. Finished sessions are left
 * out unless {@code includeFinished}, which {@code stage} FINISHED implies.
 */
public record SessionFilter(
        String search,
        SessionStage stage,
        boolean joinableOnly,
        boolean includeFinished
) {

    public static final SessionFilter NONE = new SessionFilter(null, null, false, false);

    public SessionFilter {
        search = trimToNull(search);
        search = search == null ? null : search.toLowerCase(Locale.ROOT);
        includeFinished = includeFinished || stage == SessionStage.FINISHED;
    }

    public boolean matches(GameSessionSummaryResponse summary) {
        return (includeFinished || summary.state() != GameSessionState.FINISHED_GAME)
                && (stage == null || stage == SessionStage.of(summary.state(), summary.round()))
                && (!joinableOnly || summary.joinable())
                && (search == null || contains(summary.gameTitle()) || contains(summary.hostName()));
    }

    // Case-insensitive substring match.
    private boolean contains(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(search);
    }
}
