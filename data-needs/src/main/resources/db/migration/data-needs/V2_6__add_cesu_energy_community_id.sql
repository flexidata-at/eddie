-- SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
-- SPDX-License-Identifier: Apache-2.0

ALTER TABLE cesu_join_request_data_need
    ADD COLUMN energy_community_id VARCHAR(128);
