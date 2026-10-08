package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.entity.GameRound;
import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.board.BoardCategory;
import com.nickneim.jeopardy.session.board.BoardClue;
import com.nickneim.jeopardy.session.board.BoardRound;
import com.nickneim.jeopardy.session.board.GameBoard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class GameBoardService {

    private final GameService gameService;

    public GameBoardService(GameService gameService) {
        this.gameService = gameService;
    }

    /**
     * Loads a game from the database and copies it into a {@link GameBoard}.
     * Every lazy collection is read inside this transaction, so the board can
     * be used after it ends.
     */
    @Transactional(readOnly = true)
    public GameBoard loadBoard(Long gameId) {
        return toBoard(gameService.getGame(gameId));
    }

    static GameBoard toBoard(Game game) {
        Map<GameRound, List<BoardCategory>> categoriesByRound =
                new EnumMap<>(GameRound.class);

        for (Category category : game.getCategories()) {
            List<BoardClue> clues = category.getClues()
                    .stream()
                    .map(clue -> new BoardClue(
                            clue.getId(),
                            clue.getRowNumber(),
                            clue.getQuestion(),
                            clue.getAnswer(),
                            clue.getValue(),
                            game.isDailyDouble(clue.getId())
                    ))
                    .toList();

            categoriesByRound
                    .computeIfAbsent(
                            GameRound.fromColumn(category.getColumnNumber()),
                            round -> new ArrayList<>()
                    )
                    .add(new BoardCategory(category.getName(), clues));
        }

        return new GameBoard(
                categoriesByRound.entrySet()
                        .stream()
                        .map(entry -> new BoardRound(entry.getKey(), entry.getValue()))
                        .toList()
        );
    }
}
