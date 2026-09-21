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

    private static GameClueResponse getGameClue(Clue clue) {
        return new GameClueResponse(
                clue.getId(),
                clue.getQuestion(),
                clue.getAnswer(),
                clue.getClueType(),
                clue.getMediaUrl(),
                clue.getRowNumber()
        );
    }

    private static GameCategoryResponse getGameCategory(Category category) {
        return new GameCategoryResponse(
                category.getId(),
                category.getName(),
                category.getComments(),
                category.getColumnNumber(),
                category.getClues().stream().map(GameService::getGameClue).toList()
        );
    }

    private static Long getClueIdFromGameDailyDouble(GameDailyDouble gameDailyDouble) {
        return gameDailyDouble.getClue().getId();
    }

    public GameResponse getGame(Long id) {
        Game game = gameRepository.findById(id)
                .orElseThrow(() -> new GameNotFoundException(id));

        List<GameCategoryResponse> jeopardyRound = game.getCategories().stream().filter(Category::isJeopardyRound).map(GameService::getGameCategory).toList();
        List<GameCategoryResponse> doubleJeopardyRound = game.getCategories().stream().filter(Category::isDoubleJeopardyRound).map(GameService::getGameCategory).toList();
        GameCategoryResponse finalJeopardy = game.getCategories().stream().filter(Category::isFinalJeopardy).map(GameService::getGameCategory).findFirst().orElse(null);

        List<Long> dailyDoubleClueIds = game.getDailyDoubles().stream().map(GameService::getClueIdFromGameDailyDouble).toList();

        return new GameResponse(
                game.getId(),
                game.getTitle(),
                game.getGameDate(),
                game.getSeason(),
                jeopardyRound,
                doubleJeopardyRound,
                finalJeopardy,
                dailyDoubleClueIds
        );
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
                .map(game -> new GameSummaryResponse(
                        game.getId(),
                        game.getTitle(),
                        game.getGameDate(),
                        game.getSeason()
                ))
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
