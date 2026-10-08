package com.nickneim.jeopardy.session.service;

import com.nickneim.jeopardy.game.service.GameService;
import com.nickneim.jeopardy.session.board.GameBoard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameBoardService {

    private final GameService gameService;

    public GameBoardService(GameService gameService) {
        this.gameService = gameService;
    }

    /**
     * Loads a game from the database and copies it into a {@link GameBoard}.
     * {@link GameBoard#from} reads every lazy collection inside this
     * transaction, so the board can be used after it ends.
     */
    @Transactional(readOnly = true)
    public GameBoard loadBoard(Long gameId) {
        return GameBoard.from(gameService.getGame(gameId));
    }
}
