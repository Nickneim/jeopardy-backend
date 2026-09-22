package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.repository.ClueRepository;
import org.springframework.stereotype.Service;

@Service
public class ClueService {

    private final ClueRepository clueRepository;

    public ClueService(ClueRepository clueRepository) {
        this.clueRepository = clueRepository;
    }

    public Clue getClue(Long id) {
        return clueRepository.findById(id)
                .orElseThrow(() -> new ClueNotFoundException(id));
    }
}