package com.nickneim.jeopardy.service;

import com.nickneim.jeopardy.dto.ClueResponse;
import com.nickneim.jeopardy.entity.Clue;
import com.nickneim.jeopardy.repository.ClueRepository;
import org.springframework.stereotype.Service;

@Service
public class ClueService {

    private final ClueRepository clueRepository;

    public ClueService(ClueRepository clueRepository) {
        this.clueRepository = clueRepository;
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

    private boolean isFinalClue(Clue clue) {
        return clue.getCategory().getColumnNumber() == 13;
    }

    public ClueResponse getClue(Long id) {
        Clue clue = clueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Clue not found: " + id));

        return new ClueResponse(
                clue.getId(),
                clue.getQuestion(),
                clue.getAnswer(),
                clue.getClueType(),
                clue.getMediaUrl(),

                getClueValue(clue),
                isFinalClue(clue),

                clue.getCategory().getId(),
                clue.getCategory().getName(),
                clue.getCategory().getComments()
        );
    }
}