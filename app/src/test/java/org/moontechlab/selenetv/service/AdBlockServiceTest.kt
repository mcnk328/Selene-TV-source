package org.moontechlab.selenetv.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockServiceTest {
    @Test
    fun blocksExactAdDomainAndSubdomains() {
        assertTrue(AdBlockService.isBlockedHost("doubleclick.net"))
        assertTrue(AdBlockService.isBlockedHost("secure.doubleclick.net"))
        assertTrue(AdBlockService.isBlockedHost("ADS.GOOGLESYNDICATION.COM."))
    }

    @Test
    fun doesNotBlockLookalikeOrUnrelatedHosts() {
        assertFalse(AdBlockService.isBlockedHost("notdoubleclick.net"))
        assertFalse(AdBlockService.isBlockedHost("example.com"))
        assertFalse(AdBlockService.isBlockedHost(""))
    }
}
