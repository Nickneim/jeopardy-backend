package com.nickneim.jeopardy.game.service;


import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.dto.*;
import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameDailyDouble;
import com.nickneim.jeopardy.game.repository.GameRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GameService {

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }


    public GameResponse getGame(Long id) {
        Game game = gameRepository.findById(id)
                .orElseThrow(() -> new GameNotFoundException(id));

        return GameResponse.from(game);
    }

    public PageResponse<GameSummaryResponse> getGames(int page, int size, String season) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("gameDate"),
                        Sort.Order.desc("id")
                )
        );

        Page<Game> games;
        if (season == null) {
            games = gameRepository.findAll(pageable);
        } else {
            games = gameRepository.findBySeason(season, pageable);
        }


        List<GameSummaryResponse> content = games.getContent()
                .stream()
                .map(GameSummaryResponse::from)
                .toList();

        return new PageResponse<>(
                content,
                games.getNumber(),
                games.getSize(),
                games.getTotalElements(),
                games.getTotalPages()
        );
    }

}
