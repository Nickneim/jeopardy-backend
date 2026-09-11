package com.nickneim.jeopardy.repository;

import com.nickneim.jeopardy.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}