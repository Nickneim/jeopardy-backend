package com.nickneim.jeopardy.controller;

import com.nickneim.jeopardy.dto.ClueResponse;
import com.nickneim.jeopardy.service.ClueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clues")
public class ClueController {

    private final ClueService clueService;

    public ClueController(ClueService clueService) {
        this.clueService = clueService;
    }

    @GetMapping("/{id}")
    public ClueResponse getClue(@PathVariable Long id) {
        return clueService.getClue(id);
    }
}