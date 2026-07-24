package com.skybook.praveen.authservice.dto;

import java.util.List;

/**
 * Who the caller is, from the token the server already validated
 * (FRONTEND_MODULE.md §4).
 *
 * <p>Exists because the browser's session cookie is httpOnly: the SPA cannot
 * read the token, so it asks who it is rather than decoding an unverified JWT
 * client-side. Carries no secret - only the identity and roles the caller
 * already proved.
 */
public record CurrentUserResponse(String subject, List<String> roles) {
}
