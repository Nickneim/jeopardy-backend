package com.nickneim.jeopardy.repository;

import com.nickneim.jeopardy.entity.Game;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GameRepositoryTest {

    @Autowired
    private GameRepository gameRepository;

    @Test
    void shouldFindGames() {
        List<Game> games = gameRepository.findAll();

        assertFalse(games.isEmpty());
    }

}