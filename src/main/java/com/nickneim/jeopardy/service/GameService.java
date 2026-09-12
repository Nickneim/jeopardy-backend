package com.nickneim.jeopardy.service;


import com.nickneim.jeopardy.dto.GameCategoryResponse;
import com.nickneim.jeopardy.dto.GameClueResponse;
import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.entity.Category;
import com.nickneim.jeopardy.entity.Clue;
import com.nickneim.jeopardy.entity.Game;
import com.nickneim.jeopardy.entity.GameDailyDouble;
import com.nickneim.jeopardy.repository.GameRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GameService {

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    private static int getClueValue(Clue clue) {
        short categoryColumn = clue.getCategory().getColumnNumber();
        short clueRow = clue.getRowNumber();

        if (categoryColumn <= 6) {
            return 100 * clueRow;
        }
        if (categoryColumn <= 12) {
            return 200 * clueRow;
        }
        return 2000;
    }

    private static GameClueResponse getGameClue(Clue clue) {
        return new GameClueResponse(
                clue.getId(),
                clue.getQuestion(),
                clue.getAnswer(),
                clue.getClueType(),
                clue.getMediaUrl(),
                getClueValue(clue)
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


        List<GameCategoryResponse> categories = game.getCategories().stream().map(GameService::getGameCategory).toList();
        List<GameCategoryResponse> jeopardyRound = categories.stream().filter(gameCategoryResponse -> gameCategoryResponse.columnNumber() <= 6).toList();
        List<GameCategoryResponse> doubleJeopardyRound = categories.stream().filter(category -> category.columnNumber() > 6 && category.columnNumber() <= 12).toList();
        GameCategoryResponse finalJeopardy = categories.stream().filter(category -> category.columnNumber() == 13).findAny().orElse(null);

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
