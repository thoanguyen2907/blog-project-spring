package com.myblogbackend.blog.repositories;

import com.myblogbackend.blog.models.PostEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PostRepository extends JpaRepository<PostEntity, UUID> {
    Page<PostEntity> findAllByCategoryId(Pageable pageable, UUID categoryId);

    Page<PostEntity> findAllByUserId(UUID userId, Pageable pageable);

    Page<PostEntity> findAllByUserIdAndStatusTrueOrderByCreatedDateDesc(UUID userId, Pageable pageable);
}
