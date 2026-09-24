package com.nickneim.jeopardy.session.model;

import com.nickneim.jeopardy.game.entity.Game;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameSessionTest {

    @Test
    void addPlayer_addsPlayerToSession() {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );
        UUID sessionId = UUID.randomUUID();
        GameSession session =
                new GameSession(sessionId, game);

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
