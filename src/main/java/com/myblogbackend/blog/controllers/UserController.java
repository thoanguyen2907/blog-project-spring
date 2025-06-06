package com.myblogbackend.blog.controllers;

import com.myblogbackend.blog.config.security.CurrentUser;
import com.myblogbackend.blog.config.security.UserPrincipal;
import com.myblogbackend.blog.controllers.route.CommonRoutes;
import com.myblogbackend.blog.controllers.route.UserRoutes;
import com.myblogbackend.blog.event.dto.NotificationEvent;
import com.myblogbackend.blog.request.ChangePasswordRequest;
import com.myblogbackend.blog.request.LogOutRequest;
import com.myblogbackend.blog.request.UserProfileRequest;
import com.myblogbackend.blog.response.ApiResponse;
import com.myblogbackend.blog.response.ResponseEntityBuilder;
import com.myblogbackend.blog.response.UserResponse;
import com.myblogbackend.blog.services.FcmTokenService;
import com.myblogbackend.blog.services.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.myblogbackend.blog.controllers.route.PostRoutes.PUBLIC_URL;


@RestController
@RequestMapping(CommonRoutes.BASE_API + CommonRoutes.VERSION)
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final FcmTokenService fcmTokenService;

    @GetMapping(UserRoutes.BASE_URL + "/me")
    public ResponseEntity<?> getCurrentUser() {
        UserResponse userProfile = userService.aboutMe();
        return ResponseEntityBuilder
                .getBuilder()
                .setDetails(userProfile)
                .build();
    }

    @GetMapping(PUBLIC_URL + UserRoutes.BASE_URL + "/byUserName/{userName}")
    public ResponseEntity<?> getUserProfileByUserName(final @PathVariable(value = "userName") String userName) {
        UserResponse userProfile = userService.findUserByUserName(userName);
        return ResponseEntityBuilder
                .getBuilder()
                .setDetails(userProfile)
                .build();
    }

    @GetMapping(PUBLIC_URL + UserRoutes.BASE_URL + "/top-posters")
    public ResponseEntity<?> getTopUsers(@RequestParam(defaultValue = "5") final long postThreshold,
                                         @RequestParam(defaultValue = "10") final long favoritesThreshold) {
        var postsAndHighFavorites = userService.findUsersWithManyPostsAndHighFavorites(postThreshold, favoritesThreshold);
        return ResponseEntityBuilder
                .getBuilder()
                .setDetails(postsAndHighFavorites)
                .build();
    }

    @PutMapping(UserRoutes.BASE_URL + "/changePassWord")
    public ResponseEntity<?> changePassWord(@RequestBody @Valid final ChangePasswordRequest changePasswordRequest) {
        userService.changePassword(changePasswordRequest);
        return ResponseEntityBuilder
                .getBuilder()
                .setMessage("User change password successfully!")
                .build();
    }

    @PutMapping(UserRoutes.BASE_URL + "/user-update")
    public ResponseEntity<?> updateUserProfile(final @RequestBody UserProfileRequest userProfileRequest) {
        UserResponse userProfile = userService.updateProfile(userProfileRequest);
        return ResponseEntityBuilder
                .getBuilder()
                .setDetails(userProfile)
                .build();
    }

    @PutMapping(UserRoutes.BASE_URL + "/logout")
    public ResponseEntity<ApiResponse> logoutUser(final @CurrentUser UserPrincipal currentUser,
                                                  final @Valid @RequestBody LogOutRequest logOutRequest) {
        userService.logoutUser(logOutRequest, currentUser);
        return ResponseEntity.ok(new ApiResponse(true, "User has successfully logged out from the system!"));
    }

    @GetMapping(UserRoutes.BASE_URL + "/notifications/{userId}")
    public ResponseEntity<?> getNotifications(@PathVariable(value = "userId") final UUID userId) {
        List<NotificationEvent> notificationEvents = fcmTokenService.notificationEvent(userId);
        return ResponseEntityBuilder
                .getBuilder()
                .setDetails(notificationEvents)
                .build();
    }

}