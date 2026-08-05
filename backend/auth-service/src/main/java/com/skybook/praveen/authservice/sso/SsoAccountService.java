package com.skybook.praveen.authservice.sso;

import com.skybook.praveen.authservice.entity.FederatedIdentity;
import com.skybook.praveen.authservice.entity.User;
import com.skybook.praveen.authservice.entity.UserRole;
import com.skybook.praveen.authservice.producer.EmailEventProducer;
import com.skybook.praveen.authservice.repository.FederatedIdentityRepository;
import com.skybook.praveen.authservice.repository.UserRepository;
import com.skybook.praveen.common.event.EmailEvent;
import com.skybook.praveen.common.event.EmailType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * The frozen callback decision tree (SSO_MODULE.md §4.2): find by
 * {@code (provider, sub)} → reject unverified → link by normalized email →
 * provision. Identity is keyed on the provider's stable subject, never on
 * email - a Google account's email can change; its {@code sub} cannot.
 *
 * <p><b>Deliberately NOT {@code @Transactional}:</b> both unique-violation
 * races are handled by catch-and-re-lookup, the same pattern
 * {@code AuthService.register} uses - but here a wrapping transaction would
 * have marked itself rollback-only at the violation and thrown
 * {@code UnexpectedRollbackException} at commit, turning a handled race into
 * a 500. Each repository call commits on its own; the worst orphan a lost
 * race can leave is a user row that the very next sign-in adopts by email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoAccountService {

    public static final String PROVIDER_GOOGLE = "google";

    private final UserRepository userRepository;
    private final FederatedIdentityRepository federatedIdentityRepository;
    private final EmailEventProducer emailEventProducer;

    /**
     * Resolve a verified Google identity to the SkyBook account it signs in.
     *
     * @throws SsoEmailUnverifiedException when the identity is new to us and
     *         Google does not vouch for the email address
     */
    public User resolve(String subject, String email, boolean emailVerified, String name) {

        // 1. The stable key first. A hit here signs in regardless of what the
        //    email looks like today - identity is sub, email is contact info.
        var linked = federatedIdentityRepository.findByProviderAndSubject(PROVIDER_GOOGLE, subject);
        if (linked.isPresent()) {
            return userRepository.findById(linked.get().getUserId())
                    // The FK makes this unreachable short of manual surgery on
                    // the database; failing loudly beats minting a token for a
                    // user row that is not there.
                    .orElseThrow(() -> new IllegalStateException(
                            "federated identity " + linked.get().getId() + " references a missing user"));
        }

        // 2. From here on we are creating or linking, and both trust the email
        //    - so Google must vouch for it. A missing claim counts as
        //    unverified: absence of proof is absence of proof.
        if (!emailVerified || !StringUtils.hasText(email)) {
            throw new SsoEmailUnverifiedException();
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseGet(() -> provision(normalizedEmail, name));

        FederatedIdentity identity = new FederatedIdentity();
        identity.setUserId(user.getId());
        identity.setProvider(PROVIDER_GOOGLE);
        identity.setSubject(subject);
        identity.setEmailAtLink(normalizedEmail);
        try {
            federatedIdentityRepository.save(identity);
            log.info("Linked google identity to user {}", user.getId());
        } catch (DataIntegrityViolationException race) {
            // Two arms can trip: (a) uq_provider_subject - a concurrent first
            // sign-in of the same Google account won; adopt the winner's link.
            // (b) uq_user_provider - this SkyBook account already carries a
            // DIFFERENT Google identity (the email matched but the sub didn't:
            // the user changed Google accounts). The old link stands and the
            // verified email match still signs them in - re-linking silently
            // would let the newer Google account displace the one the user
            // chose to link.
            return federatedIdentityRepository.findByProviderAndSubject(PROVIDER_GOOGLE, subject)
                    .flatMap(winner -> userRepository.findById(winner.getUserId()))
                    .orElse(user);
        }
        return user;
    }

    private User provision(String normalizedEmail, String name) {
        User user = new User();
        user.setEmail(normalizedEmail);
        // Google's 'name' claim is optional; the email's local part is an
        // honest fallback the user can edit on their profile page.
        user.setFullName(StringUtils.hasText(name)
                ? name
                : normalizedEmail.substring(0, normalizedEmail.indexOf('@')));
        // No password - this is the Google-only account shape (§4.3). The
        // supported path to a first password is forgot-password, which sets one.
        user.setRole(UserRole.USER);

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException race) {
            // Concurrent register/provision for the same email - same
            // translate-the-race pattern as AuthService.register, except here
            // the right answer is to adopt the existing account, not to 409:
            // the caller IS the owner of that verified email.
            return userRepository.findByEmail(normalizedEmail)
                    .orElseThrow(() -> race);
        }

        emailEventProducer.sendEmailEvent(EmailEvent.builder()
                .to(saved.getEmail())
                .subject("Welcome to SkyBook")
                .body("Hi " + saved.getFullName() + ", welcome to SkyBook!")
                .type(EmailType.REGISTRATION_SUCCESS)
                .build());
        log.info("Provisioned user {} from google sign-in", saved.getId());
        return saved;
    }
}
