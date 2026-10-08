package com.nickneim.jeopardy.game.service;


import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.dto.*;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.repository.GameRepository;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameService {

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }


    public Game getGame(Long id) {
        return gameRepository.findById(id)
                .orElseThrow(() -> new GameNotFoundException(id));
    }

    /**
     * Loads a game with its whole board (categories, clues and daily doubles)
     * initialized, so it can be used after the transaction ends, e.g. by a
     * live game session on a WebSocket thread.
     */
    @Transactional(readOnly = true)
    public Game getGameForPlay(Long id) {
        Game game = getGame(id);

        // Categories and clues first, so the daily doubles resolve to the
        // already-loaded clues instead of uninitialized proxies.
        game.getCategories()
                .forEach(category -> Hibernate.initialize(category.getClues()));
        Hibernate.initialize(game.getDailyDoubles());

        return game;
    }

    public Page<Game> getGames(int page, int size, String season) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("gameDate"),
                        Sort.Order.desc("id")
                )
        );

        if (season == null) {
            return gameRepository.findAll(pageable);
        }

        return gameRepository.findBySeason(season, pageable);
    }


}
