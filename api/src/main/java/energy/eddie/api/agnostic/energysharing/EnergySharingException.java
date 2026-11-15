// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

/** A stable transport-independent failure category; never contains credentials or raw market XML. */
public class EnergySharingException extends RuntimeException {
    public enum Code { INVALID_REQUEST, UNSUPPORTED, NOT_READY, NOT_FOUND, CONFLICT, FORBIDDEN }

    private final Code code;

    public EnergySharingException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
