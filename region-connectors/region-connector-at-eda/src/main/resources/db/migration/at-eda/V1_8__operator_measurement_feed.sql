-- SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
-- SPDX-License-Identifier: Apache-2.0
-- No changes to permission events, CESU, membership or transport configuration.
CREATE TABLE at_eda.operator_measurement_cursor (
    scope varchar(64) PRIMARY KEY,
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)
);
CREATE TABLE at_eda.operator_measurement_document (
    id varchar(64) PRIMARY KEY,
    scope varchar(64) NOT NULL REFERENCES at_eda.operator_measurement_cursor(scope),
    sequence bigint NOT NULL CHECK (sequence > 0),
    source_message_id varchar(35) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    payload text NOT NULL,
    payload_bytes integer NOT NULL CHECK (payload_bytes BETWEEN 1 AND 33554432),
    reading_count integer NOT NULL CHECK (reading_count BETWEEN 1 AND 100000),
    source_xml bytea NOT NULL CHECK (octet_length(source_xml) <= 16777216),
    UNIQUE (scope, sequence),
    UNIQUE (scope, source_message_id)
);
