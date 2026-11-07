package com.mau.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cloudflare Access headers go only to the HA public address's domain, never home addresses or other sites. */
class CfAccessTest {
    private val remote = "https://homeassist.example.com"

    @Test fun onlyTheHomeDomain() {
        assertTrue(Hub.cfHost("homeassist.example.com", remote))
        assertTrue(Hub.cfHost("glances-box.example.com", remote))
        assertTrue(Hub.cfHost("example.com", remote))
        assertFalse(Hub.cfHost("192.168.1.10", remote))
        assertFalse(Hub.cfHost("api.open-meteo.com", remote))
        assertFalse(Hub.cfHost("notexample.com", remote)) // a lookalike, not a subdomain
        assertFalse(Hub.cfHost(null, remote))
        assertFalse(Hub.cfHost("homeassist.example.com", "")) // no public address set: never
    }
}
