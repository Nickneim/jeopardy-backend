package com.nickneim.jeopardy.service;


import com.nickneim.jeopardy.dto.GameCategoryResponse;
import com.nickneim.jeopardy.dto.GameClueResponse;
import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.entity.*;
import com.nickneim.jeopardy.repository.GameRepository;
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
                clue.getValue()
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

    public GameResponse getGame(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new RuntimeException("Game not found: " + gameId));

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
}
