package com.nightvenue.api.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.nightvenue.api.auth.AuthExceptions.InvalidAccessToken;
import static com.nightvenue.api.auth.AuthExceptions.VenueNotFound;

@Service
public class VenueAccessService {
    private final AuthUserRepository users;

    public VenueAccessService(AuthUserRepository users) {
        this.users = users;
    }

    public List<Long> venueIdsFor(UserAccount user) {
        return users.activeVenueIds(user.id(), user.role());
    }

    public void requireAccess(long venueId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new InvalidAccessToken();
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException ex) {
            throw new InvalidAccessToken();
        }
        UserAccount user = users.findById(userId).filter(UserAccount::active).orElseThrow(InvalidAccessToken::new);
        if (!venueIdsFor(user).contains(venueId)) {
            throw new VenueNotFound();
        }
    }
}
