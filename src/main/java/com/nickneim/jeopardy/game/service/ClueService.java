package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.dto.ClueResponse;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.repository.ClueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClueService {

    private final ClueRepository clueRepository;

    public ClueService(ClueRepository clueRepository) {
        this.clueRepository = clueRepository;
    }

    // The response reads the clue's lazy category, so it's built inside this
    // transaction.
    @Transactional(readOnly = true)
    public ClueResponse getClue(Long id) {
        return clueRepository.findById(id)
                .map(ClueResponse::from)
                .orElseThrow(() -> new ClueNotFoundException(id));
    }
}
