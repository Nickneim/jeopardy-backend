package com.nickneim.jeopardy.controller;

import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.dto.GameSummaryResponse;
import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.service.GameService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/{id}")
    public GameResponse getClue(@PathVariable Long id) {
        return gameService.getGame(id);
    }

    @GetMapping
    public PageResponse<GameSummaryResponse> getGames(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return gameService.getGames(page, size);
    }
}