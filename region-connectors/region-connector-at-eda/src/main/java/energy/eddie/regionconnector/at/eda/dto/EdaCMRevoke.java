// SPDX-FileCopyrightText: 2024 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.dto;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

public interface EdaCMRevoke {
    String meteringPoint();

    String consentId();

    LocalDate consentEnd();

    default @Nullable String senderMessageAddress() {return null;}

    default @Nullable String receiverMessageAddress() {return null;}
}
