package com.myblogbackend.blog.services;

import java.util.UUID;

public interface FavoriteService {
    void persistOrDelete(UUID postId);

    void persistOrDeleteV2(UUID postId);
}
