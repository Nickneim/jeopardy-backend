package com.nickneim.jeopardy.service;

import com.nickneim.jeopardy.dto.ClueResponse;
import com.nickneim.jeopardy.entity.Clue;
import com.nickneim.jeopardy.exception.ClueNotFoundException;
import com.nickneim.jeopardy.repository.ClueRepository;
import org.springframework.stereotype.Service;

@Service
public class ClueService {

    private final ClueRepository clueRepository;

    public ClueService(ClueRepository clueRepository) {
        this.clueRepository = clueRepository;
    }

    public ClueResponse getClue(Long id) {
        Clue clue = clueRepository.findById(id)
                .orElseThrow(() -> new ClueNotFoundException(id));

        return new ClueResponse(
                clue.getId(),
                clue.getQuestion(),
                clue.getAnswer(),
                clue.getClueType(),
                clue.getMediaUrl(),

                clue.getValue(),
                clue.isFinalClue(),

                clue.getCategory().getId(),
                clue.getCategory().getName(),
                clue.getCategory().getComments()
        );
    }
}