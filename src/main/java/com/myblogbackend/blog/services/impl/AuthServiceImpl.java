package com.myblogbackend.blog.services.impl;

import com.myblogbackend.blog.config.security.JwtProvider;
import com.myblogbackend.blog.enums.NotificationType;
import com.myblogbackend.blog.enums.OAuth2Provider;
import com.myblogbackend.blog.enums.RoleName;
import com.myblogbackend.blog.exception.TokenRefreshException;
import com.myblogbackend.blog.exception.commons.BlogRuntimeException;
import com.myblogbackend.blog.exception.commons.ErrorCode;
import com.myblogbackend.blog.feign.OutboundIdentityClient;
import com.myblogbackend.blog.feign.OutboundUserClient;
import com.myblogbackend.blog.mapper.UserMapper;
import com.myblogbackend.blog.models.RefreshTokenEntity;
import com.myblogbackend.blog.models.RoleEntity;
import com.myblogbackend.blog.models.UserDeviceEntity;
import com.myblogbackend.blog.models.UserEntity;
import com.myblogbackend.blog.models.UserVerificationTokenEntity;
import com.myblogbackend.blog.repositories.RefreshTokenRepository;
import com.myblogbackend.blog.repositories.RoleRepository;
import com.myblogbackend.blog.repositories.UserDeviceRepository;
import com.myblogbackend.blog.repositories.UserTokenRepository;
import com.myblogbackend.blog.repositories.UsersRepository;
import com.myblogbackend.blog.request.DeviceInfoRequest;
import com.myblogbackend.blog.request.ExchangeTokenRequest;
import com.myblogbackend.blog.request.ForgotPasswordRequest;
import com.myblogbackend.blog.request.LoginFormOutboundRequest;
import com.myblogbackend.blog.request.LoginFormRequest;
import com.myblogbackend.blog.request.SignUpFormRequest;
import com.myblogbackend.blog.request.TokenRefreshRequest;
import com.myblogbackend.blog.response.JwtResponse;
import com.myblogbackend.blog.response.UserResponse;
import com.myblogbackend.blog.services.AuthService;
import com.myblogbackend.blog.strategyPattern.MailFactory;
import com.myblogbackend.blog.strategyPattern.MailStrategy;
import lombok.experimental.NonFinal;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static com.myblogbackend.blog.enums.NotificationType.EMAIL_FORGOT_PASSWORD;
import static com.myblogbackend.blog.enums.NotificationType.EMAIL_REGISTRATION_CONFIRMATION;
import static com.myblogbackend.blog.utils.SlugUtil.splitFromEmail;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger logger = LogManager.getLogger(AuthServiceImpl.class);
    public static final long ONE_DAY_IN_MILLIS = 86400000;
    public static final String JSON = "json";
    public static final String TEMPLATES_INVALIDTOKEN_HTML = "/templates/invalidtoken.html";
    public static final String TEMPLATES_ALREADYCONFIRMED_HTML = "/templates/alreadyconfirmed.html";
    public static final String TEMPLATES_EMAIL_ACTIVATED_HTML = "/templates/emailActivated.html";

    @NonFinal
    @Value("${outbound.identity.client-id}")
    private String CLIENT_ID;

    @NonFinal
    @Value("${outbound.identity.client-secret}")
    private String CLIENT_SECRET;

    @NonFinal
    @Value("${outbound.identity.redirect-uri}")
    private String REDIRECT_URI;
    private static final String GRANT_TYPE = "authorization_code";
    private final UsersRepository usersRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtProvider jwtProvider;
    private final UserDeviceRepository userDeviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder encoder;
    private final UserMapper userMapper;
    private final MailStrategy mailStrategy;
    private final UserTokenRepository userTokenRepository;
    private final RoleRepository roleRepository;
    private final OutboundIdentityClient outboundIdentityClient;
    private final OutboundUserClient outboundUserClient;


    public AuthServiceImpl(final UsersRepository usersRepository, final AuthenticationManager authenticationManager,
                           final JwtProvider jwtProvider, final UserDeviceRepository userDeviceRepository,
                           final RefreshTokenRepository refreshTokenRepository, final PasswordEncoder encoder,
                           final UserMapper userMapper, final MailFactory mailFactory, final UserTokenRepository userTokenRepository,
                           final RoleRepository roleRepository, final OutboundIdentityClient outboundIdentityClient,
                           final OutboundUserClient outboundUserClient) {
        this.usersRepository = usersRepository;
        this.authenticationManager = authenticationManager;
        this.jwtProvider = jwtProvider;
        this.userDeviceRepository = userDeviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.encoder = encoder;
        this.userMapper = userMapper;
        this.mailStrategy = mailFactory.createStrategy();
        this.userTokenRepository = userTokenRepository;
        this.roleRepository = roleRepository;
        this.outboundIdentityClient = outboundIdentityClient;
        this.outboundUserClient = outboundUserClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserResponse registerUser(final SignUpFormRequest signUpRequest) {
        var userEntityOpt = usersRepository.findByEmail(signUpRequest.getEmail());
        if (userEntityOpt.isPresent()) {
            logger.warn("Account already exists '{}'", userEntityOpt.get().getEmail());
            throw new BlogRuntimeException(ErrorCode.ALREADY_EXIST);
        }
        var roles = new HashSet<RoleEntity>();
        var userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new BlogRuntimeException(ErrorCode.ID_NOT_FOUND));
        roles.add(userRole);
        var newUser = new UserEntity();
        newUser.setEmail(signUpRequest.getEmail());
        newUser.setPassword(encoder.encode(signUpRequest.getPassword()));
        newUser.setActive(true);
        newUser.setIsPending(true);
        newUser.setFollowers(0L);
        newUser.setName(signUpRequest.getName());
        newUser.setUserName(splitFromEmail(signUpRequest.getEmail()));
        newUser.setProvider(OAuth2Provider.LOCAL);
        newUser.setRoles(roles);

        var result = usersRepository.save(newUser);
        logger.info("Created user successfully '{}'", result);

        if (result.getActive()) {
            logger.info("Sending activation email to '{}'", result);
            var token = UUID.randomUUID().toString();
            createVerificationToken(result, token, EMAIL_REGISTRATION_CONFIRMATION);
            try {
                mailStrategy.sendActivationEmail(result, token);
            } catch (Exception e) {
                // Roll back the transaction if email sending fails
                logger.error("Failed to send activation email", e);
                throw new BlogRuntimeException(ErrorCode.EMAIL_SEND_FAILED);
            }
        }

        return userMapper.toUserDTO(result);
    }

    @Override
    public ResponseEntity<?> confirmationEmail(final String token) throws IOException {
        UserVerificationTokenEntity verificationToken = userTokenRepository.findByVerificationToken(token);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.TEXT_HTML);

        if (verificationToken == null || isTokenExpired(verificationToken)) {
            return loadHtmlTemplate(TEMPLATES_INVALIDTOKEN_HTML, responseHeaders);
        }

        UserEntity userEntity = verificationToken.getUser();
        if (!userEntity.getIsPending()) {
            return loadHtmlTemplate(TEMPLATES_ALREADYCONFIRMED_HTML, responseHeaders);
        }
        userEntity.setIsPending(false);
        userEntity.setActive(false);
        usersRepository.save(userEntity);
        return loadHtmlTemplate(TEMPLATES_EMAIL_ACTIVATED_HTML, responseHeaders);
    }


    @Override
    public void createVerificationToken(final UserEntity userEntity, final String token,
                                        final NotificationType notificationType) {
        var exp = 0;
        if (notificationType.equals(EMAIL_REGISTRATION_CONFIRMATION)) {
            exp = 15;
        } else if (notificationType.equals(EMAIL_FORGOT_PASSWORD)) {
            exp = 20;
        }
        UserVerificationTokenEntity myToken = UserVerificationTokenEntity.builder()
                .verificationToken(token)
                .user(userEntity)
                .expDate(calculateExpiryDate(exp))
                .build();
        userTokenRepository.save(myToken);
    }

    @Override
    public JwtResponse outboundAuthentication(final LoginFormOutboundRequest loginFormOutboundRequest) {

        var response = outboundIdentityClient.exchangeToken(ExchangeTokenRequest.builder()
                .code(loginFormOutboundRequest.getCode())
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .redirectUri(REDIRECT_URI)
                .grantType(GRANT_TYPE)
                .build());

        logger.info("Token from google {} ", response.getAccessToken());
        var userInfo = outboundUserClient.getUserInfo(JSON, response.getAccessToken());

        var user = usersRepository.findByEmail(userInfo.getEmail())
                .orElseGet(() -> createNewUser(userInfo.getEmail(), userInfo.getName()));

        var jwtToken = jwtProvider.generateTokenFromUser(user);
        var refreshTokenEntity = createRefreshToken(loginFormOutboundRequest.getDeviceInfo(), user);

        return JwtResponse.builder()
                .accessToken(jwtToken)
                .refreshToken(refreshTokenEntity.getToken())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forgotPassword(final ForgotPasswordRequest forgotPasswordDto) {
        UserEntity userEntity = checkValidUserLogin(forgotPasswordDto.getEmail());
        if (!userEntity.getActive()) {
            logger.info("Sending activation email to '{}'", userEntity);
            var newPassword = UUID.randomUUID().toString().substring(0, 8);
            userEntity.setPassword(encoder.encode(newPassword));
            UserEntity result = usersRepository.save(userEntity);
            createVerificationToken(result, newPassword, EMAIL_FORGOT_PASSWORD);
            try {
                mailStrategy.sendForgotPasswordEmail(result, newPassword);
            } catch (Exception e) {
                // Roll back the transaction if email sending fails
                logger.error("Failed to send activation email", e);
                throw new BlogRuntimeException(ErrorCode.EMAIL_SEND_FAILED);
            }
        }
    }

    private UserEntity checkValidUserLogin(final String forgotPasswordDto) {
        UserEntity userEntity = usersRepository
                .findByEmail(forgotPasswordDto)
                .orElseThrow(() -> new RuntimeException("Fail! -> Cause: User not found."));
        if (!userEntity.getProvider().equals(OAuth2Provider.LOCAL)) {
            throw new RuntimeException("Email already existed with sign in by oauth2 method!");
        }
        return userEntity;
    }

    private UserEntity createNewUser(final String email, final String name) {
        var roles = new HashSet<RoleEntity>();
        var userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new BlogRuntimeException(ErrorCode.COULD_NOT_FOUND));
        roles.add(userRole);
        var user = UserEntity.builder()
                .provider(OAuth2Provider.GOOGLE)
                .email(email)
                .followers(0L)
                .name(name)
                .userName(splitFromEmail(email))
                .active(false)
                .isPending(false)
                .roles(roles)
                .build();
        usersRepository.save(user);
        return user;
    }

    @Override
    public JwtResponse userLogin(final LoginFormRequest loginRequest) {
        var userEntity = usersRepository.findByEmail(loginRequest.getEmail())
                .orElseThrow(() -> new BlogRuntimeException(ErrorCode.USER_COULD_NOT_FOUND));

        if (userEntity.getActive()) {
            throw new BlogRuntimeException(ErrorCode.USER_ACCOUNT_IS_NOT_ACTIVE);
        }
        var authentication = authenticateUser(loginRequest);

        SecurityContextHolder.getContext().setAuthentication(authentication);
        var jwtToken = jwtProvider.generateJwtToken(userEntity);
        var refreshTokenEntity = createRefreshToken(loginRequest.getDeviceInfo(), userEntity);
        return new JwtResponse(jwtToken, refreshTokenEntity.getToken());
    }


    @Override
    public JwtResponse refreshJwtToken(final TokenRefreshRequest tokenRefreshRequest) {
        var requestRefreshToken = tokenRefreshRequest.getRefreshToken();
        var token = Optional.of(refreshTokenRepository.findByToken(requestRefreshToken)
                .map(refreshToken -> {
                    verifyExpiration(refreshToken);
                    verifyRefreshAvailability(refreshToken);
                    increaseCount(refreshToken);
                    return refreshToken;
                })
                .map(RefreshTokenEntity::getUserDevice)
                .map(UserDeviceEntity::getUser)
                .map(jwtProvider::generateTokenFromUser)
                .orElseThrow(() -> new TokenRefreshException(requestRefreshToken, "Missing refresh token in database.Please login again")));
        return new JwtResponse(token.get(), tokenRefreshRequest.getRefreshToken());
    }

    private Date calculateExpiryDate(final int expiryTimeInMinutes) {
        var cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, expiryTimeInMinutes);
        return new Date(cal.getTime().getTime());
    }


    private void verifyExpiration(final RefreshTokenEntity token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            throw new TokenRefreshException(token.getToken(), "Expired token. Please issue a new request");
        }
    }

    private void verifyRefreshAvailability(final RefreshTokenEntity refreshTokenEntity) {
        var userDeviceEntity = userDeviceRepository.findByRefreshToken(refreshTokenEntity)
                .orElseThrow(() -> new TokenRefreshException(refreshTokenEntity.getToken(), "No device found for the matching token. Please login again"));
        if (!userDeviceEntity.getIsRefreshActive()) {
            throw new TokenRefreshException(refreshTokenEntity.getToken(), "Refresh blocked for the device. Please login through a different device");
        }
    }

    private void increaseCount(final RefreshTokenEntity refreshTokenEntity) {
        refreshTokenEntity.incrementRefreshCount();
        refreshTokenRepository.save(refreshTokenEntity);
    }

    private UserDeviceEntity createUserDevice(final DeviceInfoRequest deviceInfoRequest) {
        return UserDeviceEntity.builder()
                .deviceId(deviceInfoRequest.getDeviceId())
                .deviceType(deviceInfoRequest.getDeviceType())
                .isRefreshActive(true)
                .build();
    }

    private RefreshTokenEntity createRefreshToken() {
        return RefreshTokenEntity.builder()
                .expiryDate(Instant.now().plusMillis(ONE_DAY_IN_MILLIS))
                .token(jwtProvider.generateRefreshTokenToken(Instant.now().plusMillis(ONE_DAY_IN_MILLIS)))
                .refreshCount(0L)
                .build();
    }

    private Authentication authenticateUser(final LoginFormRequest loginRequest) {
        try {
            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getEmail(),
                            loginRequest.getPassword()
                    )
            );
        } catch (BadCredentialsException e) {
            throw new BlogRuntimeException(ErrorCode.UNAUTHORIZED);
        }
    }

    private RefreshTokenEntity createRefreshToken(final DeviceInfoRequest deviceInfoRequest, final UserEntity userEntity) {
        userDeviceRepository.findByUserId(userEntity.getId())
                .map(UserDeviceEntity::getRefreshToken)
                .map(RefreshTokenEntity::getId)
                .ifPresent(refreshTokenRepository::deleteById);

        var userDeviceEntity = createUserDevice(deviceInfoRequest);
        var refreshTokenEntity = createRefreshToken();
        userDeviceEntity.setUser(userEntity);
        userDeviceEntity.setRefreshToken(refreshTokenEntity);
        refreshTokenEntity.setUserDevice(userDeviceEntity);
        refreshTokenEntity = refreshTokenRepository.save(refreshTokenEntity);
        return refreshTokenEntity;
    }

    private boolean isTokenExpired(final UserVerificationTokenEntity verificationToken) {
        long timeDifference = verificationToken.getExpDate().getTime() - Calendar.getInstance().getTime().getTime();
        return timeDifference <= 0;
    }

    private ResponseEntity<String> loadHtmlTemplate(final String templatePath,
                                                    final HttpHeaders headers) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(templatePath)) {
            if (in != null) {
                String result = IOUtils.toString(in, StandardCharsets.UTF_8);
                return new ResponseEntity<>(result, headers, HttpStatus.OK);
            }
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }
}
