// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.es.datadis.dtos.exceptions;

/**
 * Indicates that Datadis has not exposed the accepted CUPS authorization yet.
 *
 * <p>The Datadis portal and its API are eventually consistent. Treating an empty
 * authorization response as a completed reactive stream leaves the permission in
 * {@code SENT_TO_PERMISSION_ADMINISTRATOR} forever. This exception makes the
 * temporary state explicit so the acceptance flow can retry it for a bounded time.</p>
 */
public class AuthorizationNotVisibleException extends Exception {
    public AuthorizationNotVisibleException(String permissionId) {
        super("Datadis has not exposed the authorized CUPS for permission request " + permissionId + " yet");
    }
}
