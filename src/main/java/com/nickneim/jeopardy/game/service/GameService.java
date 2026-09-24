package com.nickneim.jeopardy.game.service;


import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.dto.*;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.repository.GameRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

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
