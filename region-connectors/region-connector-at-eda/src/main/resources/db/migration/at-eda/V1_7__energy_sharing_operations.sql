-- SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE at_eda.energy_sharing_record (
    id varchar(64) PRIMARY KEY,
    scope varchar(64) NOT NULL,
    kind varchar(24) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    market_id varchar(35) UNIQUE,
    payload text NOT NULL,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX energy_sharing_scope_kind ON at_eda.energy_sharing_record(scope, kind);
