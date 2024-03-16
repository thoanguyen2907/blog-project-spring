package com.myblogbackend.blog.services.impl;

import com.myblogbackend.blog.exception.commons.BlogRuntimeException;
import com.myblogbackend.blog.exception.commons.ErrorCode;
import com.myblogbackend.blog.mapper.PostMapper;
import com.myblogbackend.blog.models.CategoryEntity;
import com.myblogbackend.blog.models.PostEntity;
import com.myblogbackend.blog.pagination.OffsetPageRequest;
import com.myblogbackend.blog.pagination.PaginationPage;
import com.myblogbackend.blog.repositories.CategoryRepository;
import com.myblogbackend.blog.repositories.PostRepository;
import com.myblogbackend.blog.repositories.UsersRepository;
import com.myblogbackend.blog.request.PostRequest;
import com.myblogbackend.blog.response.PostResponse;
import com.myblogbackend.blog.services.PostService;
import com.myblogbackend.blog.utils.GsonUtils;
import com.myblogbackend.blog.utils.JWTSecurityUtil;
import com.myblogbackend.blog.webclient.PostWebClient;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {
    private static final Logger logger = LogManager.getLogger(PostServiceImpl.class);
    private final PostRepository postRepository;
    private final CategoryRepository categoryRepository;
    private final UsersRepository usersRepository;
    private final PostMapper postMapper;
    private final PostWebClient postWebClient;

    @Override
    public PostResponse createPost(final PostRequest postRequest) {
        try {
            var signedInUser = JWTSecurityUtil.getJWTUserInfo().orElseThrow();
            var category = validateCategory(postRequest.getCategoryId());
            var postEntity = postMapper.toPostEntity(postRequest);
            postEntity.setCategory(category);
            postEntity.setStatus("active");
            postEntity.setApproved(Boolean.TRUE);
            postEntity.setUser(usersRepository.findById(signedInUser.getId()).orElseThrow());
            var createdPost = postRepository.save(postEntity);
            logger.info("Post was created with id: {}", createdPost.getId());
            return postMapper.toPostResponse(createdPost);
        } catch (Exception e) {
            logger.error("Failed to create post", e);
            throw new RuntimeException("Failed to create post");
        }
    }

    @Override
    public PaginationPage<PostResponse> getAllPostsByUserId(final Integer offset, final Integer limited) {
        try {
            var signedInUser = JWTSecurityUtil.getJWTUserInfo().orElseThrow();
            var pageable = new OffsetPageRequest(offset, limited);
            var postEntities = postRepository.findAllByUserId(signedInUser.getId(), pageable);
            logger.info("Post get succeeded with offset: {} and limited {}", postEntities.getNumber(), postEntities.getSize());
            return getPostResponsePaginationPage(postEntities);
        } catch (Exception e) {
            logger.error("Failed to get list post", e);
            throw new RuntimeException("Failed to get list post");
        }
    }

    @Override
    public PaginationPage<PostResponse> getAllPostsByCategoryId(final Integer offset, final Integer limited, final UUID categoryId) {
        try {
            var pageable = new OffsetPageRequest(offset, limited);
            var posts = postRepository.findAllByCategoryId(pageable, categoryId);
            if (posts.getContent().size() == 0) {
                postWebClient.getListOfPostsReactive();
            }
            logger.info("Post get succeeded with offset: {} and limited {}", posts.getNumber(), posts.getSize());
            return getPostResponsePaginationPage(posts);
        } catch (Exception e) {
            logger.error("Failed to  get all posts by category id", e);
            throw new RuntimeException("Failed to get all posts by category id");
        }
    }

    @Override
    public PostResponse getPostById(final UUID id) {
        try {
            var post = postRepository
                    .findById(id)
                    .orElseThrow(() -> new BlogRuntimeException(ErrorCode.ID_NOT_FOUND));
            logger.error("Get post successfully by id {} ", id);
            // if the post is null, trigger the web client to fetch post form url
            if (post == null) {
                postWebClient.getMonoPost(id);
            }
            return postMapper.toPostResponse(post);
        } catch (Exception e) {
            logger.error("Failed to get post by id", e);
            throw new RuntimeException("Failed to get post by id");
        }
    }

    @Override
    public PostResponse updatePost(final UUID id, final PostRequest postRequest) {
        try {
            var post = postRepository.findById(id)
                    .orElseThrow(() -> new BlogRuntimeException(ErrorCode.ID_NOT_FOUND));
            var category = validateCategory(postRequest.getCategoryId());
            post.setTitle(postRequest.getTitle());
            post.setContent(postRequest.getContent());
            post.setImages(GsonUtils.arrayToString(postRequest.getImages()));
            post.setThumnails(GsonUtils.arrayToString(postRequest.getThumnails()));
            post.setCategory(category);
            var updatedPost = postRepository.save(post);
            logger.info("Update post successfully with id {} ", id);
            return postMapper.toPostResponse(updatedPost);
        } catch (Exception e) {
            logger.error("Failed to update post by id", e);
            throw new RuntimeException("Failed to update post by id");
        }
    }

    private CategoryEntity validateCategory(final UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BlogRuntimeException(ErrorCode.ID_NOT_FOUND));
    }

    private PaginationPage<PostResponse> getPostResponsePaginationPage(final Page<PostEntity> postEntities) {
        var postResponses = postEntities.getContent().stream()
                .map(postMapper::toPostResponse)
                .collect(Collectors.toList());
        return new PaginationPage<PostResponse>()
                .setRecords(postResponses)
                .setOffset(postEntities.getNumber())
                .setLimit(postEntities.getSize())
                .setTotalRecords(postEntities.getTotalElements());
    }
}
