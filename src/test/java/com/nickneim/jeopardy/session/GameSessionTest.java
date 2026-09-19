package com.nickneim.jeopardy.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameSessionTest {

    @Test
    void addPlayer_addsPlayerToSession() {
        GameSession session =
                new GameSession(UUID.randomUUID());

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
}
