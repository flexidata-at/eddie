// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.requests;

import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdaGroupingIdFactoryTest {
    private static final ZonedDateTime DATE_TIME = ZonedDateTime.parse("2026-09-01T12:00:00Z");

    @Test
    void create_withPrefix_prependsPrefix() {
        var factory = new EdaGroupingIdFactory(new AtConfiguration("EP123456", null, "DEV1"));

        var result = factory.create(DATE_TIME);

        assertEquals("DEV1EP123456T1788264000000", result);
    }

    @Test
    void create_withoutPrefix_usesPartyId() {
        var factory = new EdaGroupingIdFactory(new AtConfiguration("EP123456", null, ""));

        var result = factory.create(DATE_TIME);

        assertEquals("EP123456T1788264000000", result);
    }

    @Test
    void randomIdsPreserveRoutingPrefixWithinOfficialGroupingIdConstraints() {
        for (var prefix : new String[]{"", "DATADEV", "ABCDEFGHIJKLM"}) {
            var factory = new EdaGroupingIdFactory(new AtConfiguration("EP123456", null, prefix));
            var first = factory.createRandomId();
            var second = factory.createRandomId();

            assertTrue(first.startsWith(prefix));
            assertTrue(first.matches("[0-9A-Za-z]{1,35}"));
            assertTrue(second.matches("[0-9A-Za-z]{1,35}"));
            assertNotEquals(first, second);
        }
    }
}
