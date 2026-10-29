package com.nightvenue.api.auth;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nightvenue.api.common.InvalidRequest;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.nightvenue.api.auth.AuthExceptions.InvalidAccessToken;
import static com.nightvenue.api.auth.AuthExceptions.InvalidCredentials;
import static com.nightvenue.api.auth.AuthExceptions.InvalidRefreshToken;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthUserRepository users;
    private final RefreshTokenStore sessions;
    private final AuthAuditService audit;
    private final VenueAccessService venues;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(
            AuthUserRepository users,
            RefreshTokenStore sessions,
            AuthAuditService audit,
            VenueAccessService venues,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AuthProperties properties,
            Clock clock,
            String dummyPasswordHash) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.venues = venues;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = dummyPasswordHash;
    }

    @Transactional
    public AuthSession login(String emailInput, String password) {
        if (emailInput == null || emailInput.isBlank() || password == null || password.isBlank()) {
            throw new InvalidRequest("Email and password are required");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRequest("Password exceeds 72 UTF-8 bytes");
        }

        String email = emailInput.trim().toLowerCase(Locale.ROOT);
        UserAccount user = users.findByEmail(email).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(password,
                user == null ? dummyPasswordHash : user.passwordHash());
        if (user == null || !user.active() || !passwordMatches) {
            throw new InvalidCredentials();
        }

        String rawToken = newRefreshToken();
        String familyId = UUID.randomUUID().toString();
        sessions.create(user.id(), familyId, TokenDigests.sha256(rawToken), properties.getRefreshTtl().toSeconds());
        users.updateLastLogin(user.id(), clock.instant());
        return new AuthSession(response(user), rawToken);
    }

    public AuthSession refresh(String rawToken, String clientIp) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshToken();
        }
        String oldHash = TokenDigests.sha256(rawToken);
        String newRawToken = newRefreshToken();
        String result = sessions.rotate(oldHash, TokenDigests.sha256(newRawToken),
                properties.getRefreshTtl().toSeconds());

        if (result.startsWith("REUSED:")) {
            String[] parts = result.split(":", 4);
            long userId = Long.parseLong(parts[1]);
            String familyId = parts[2];
            int familyCount = Integer.parseInt(parts[3]);
            audit.refreshReuse(userId, familyId, clientIp);
            audit.sessionsRevoked(userId, users.findById(userId).isPresent(), "REFRESH_TOKEN_REUSE", familyCount);
            throw new InvalidRefreshToken();
        }
        if (!result.startsWith("OK:")) {
            throw new InvalidRefreshToken();
        }

        long userId = Long.parseLong(result.split(":", 3)[1]);
        UserAccount user = users.findById(userId).orElse(null);
        if (user == null || !user.active()) {
            int familyCount = sessions.revokeAll(userId);
            audit.sessionsRevoked(userId, user != null, "USER_INACTIVE", familyCount);
            throw new InvalidRefreshToken();
        }
        return new AuthSession(response(user), newRawToken);
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            sessions.logout(TokenDigests.sha256(rawToken));
        }
    }

    public UserProfile me(String subject) {
        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException ex) {
            throw new InvalidAccessToken();
        }
        UserAccount user = users.findById(userId).filter(UserAccount::active).orElseThrow(InvalidAccessToken::new);
        return profile(user);
    }

    private AuthResponse response(UserAccount user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getAccessTtl());
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("nightvenue")
                        .subject(Long.toString(user.id()))
                        .claim("role", user.role())
                        .issuedAt(now)
                        .expiresAt(expiresAt)
                        .build())).getTokenValue();
        return new AuthResponse(token, properties.getAccessTtl().toSeconds(), profile(user));
    }

    private UserProfile profile(UserAccount user) {
        return new UserProfile(user.id(), user.email(), user.fullName(), user.role(), venues.venueIdsFor(user));
    }

    private static String newRefreshToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record AuthResponse(String accessToken, long expiresIn, UserProfile user) {
    }

    public record AuthSession(AuthResponse response, String refreshToken) {
    }

    public record UserProfile(long id, String email, String fullName, String role, List<Long> venueIds) {
    }
}
