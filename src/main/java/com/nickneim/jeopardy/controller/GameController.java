package com.nickneim.jeopardy.controller;

import com.nickneim.jeopardy.dto.GameResponse;
import com.nickneim.jeopardy.service.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}