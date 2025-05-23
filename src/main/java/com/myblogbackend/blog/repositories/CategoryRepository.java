package com.myblogbackend.blog.repositories;

import com.myblogbackend.blog.models.CategoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    Page<CategoryEntity> findAllByStatusTrue(Pageable pageable);

    Optional<CategoryEntity> findByName(String name);

}
