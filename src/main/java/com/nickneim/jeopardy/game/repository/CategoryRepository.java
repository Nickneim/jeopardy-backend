package com.nickneim.jeopardy.game.repository;

import com.nickneim.jeopardy.game.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}