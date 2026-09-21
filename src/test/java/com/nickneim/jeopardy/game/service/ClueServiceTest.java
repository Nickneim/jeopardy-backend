package com.nickneim.jeopardy.game.service;

import com.nickneim.jeopardy.game.dto.ClueResponse;
import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.exception.ClueNotFoundException;
import com.nickneim.jeopardy.game.repository.ClueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClueServiceTest {

    @Mock
    private ClueRepository clueRepository;

    @InjectMocks
    private ClueService clueService;

    @Test
    void getClue_mapsClueToResponse() throws Exception {
        Game game = new Game(
                "Show #123",
                LocalDate.of(2025, 1, 1),
                "40"
        );

        Category category = new Category(
                "HISTORY",
                (short) 1,
                "Some comments",
                game
        );

        Clue clue = new Clue(
                "Who was the first president of the United States?",
                "Who is George Washington?",
                (short) 3,
                ClueType.TEXT,
                null,
                category
        );

        when(clueRepository.findById(123L))
                .thenReturn(Optional.of(clue));

        ClueResponse response = clueService.getClue(123L);

        assertEquals(clue.getId(), response.id());
        assertEquals(clue.getQuestion(), response.question());
        assertEquals(clue.getAnswer(), response.answer());
        assertEquals(clue.getClueType(), response.clueType());
        assertEquals(clue.getMediaUrl(), response.mediaUrl());

        assertEquals(clue.getValue(), response.value());
        assertEquals(clue.isFinalClue(), response.isFinalClue());

        assertEquals(category.getId(), response.categoryId());
        assertEquals(category.getName(), response.categoryName());
        assertEquals(category.getComments(), response.categoryComments());
    }

    @Test
    void getClue_throwsWhenClueDoesNotExist() {
        when(clueRepository.findById(123L))
                .thenReturn(Optional.empty());

        assertThrows(
                ClueNotFoundException.class,
                () -> clueService.getClue(123L)
        );

        verify(clueRepository).findById(123L);
    }

}