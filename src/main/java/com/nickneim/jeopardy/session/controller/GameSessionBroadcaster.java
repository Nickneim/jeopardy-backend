package com.nickneim.jeopardy.session.controller;

import com.nickneim.jeopardy.session.dto.GameSessionResponse;
import com.nickneim.jeopardy.session.dto.TrebekViewResponse;
import com.nickneim.jeopardy.session.model.GameSession;
import com.nickneim.jeopardy.session.model.GameSessionPlayer;
import com.nickneim.jeopardy.session.model.SessionCode;
import com.nickneim.jeopardy.session.security.PlayerPrincipal;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class GameSessionBroadcaster {

    /** Trebek's private view, as a user destination (/user/queue/trebek). */
    public static final String TREBEK_QUEUE = "/queue/trebek";

    private final SimpMessagingTemplate messagingTemplate;

    public GameSessionBroadcaster(
            SimpMessagingTemplate messagingTemplate
    ) {
        this.messagingTemplate = messagingTemplate;
    }

    /** Where a session's state is broadcast. */
    public static String topic(SessionCode sessionId) {
        return "/topic/sessions/" + sessionId;
    }

    /**
     * Sends the public state to the session's topic, and in a Trebek session
     * Trebek's private view to Trebek alone.
     */
    public void broadcast(GameSession session) {
        messagingTemplate.convertAndSend(
                topic(session.getId()),
                GameSessionResponse.from(session)
        );

        sendTrebekView(session);
    }

    /** Sends Trebek's private view to every connection of Trebek's, if the session has one. */
    public void sendTrebekView(GameSession session) {
        GameSessionPlayer trebek = session.getTrebek();

        if (trebek == null) {
            return;
        }

        messagingTemplate.convertAndSendToUser(
                PlayerPrincipal.of(session, trebek.getId()).getName(),
                TREBEK_QUEUE,
                TrebekViewResponse.from(session)
        );
    }
}
