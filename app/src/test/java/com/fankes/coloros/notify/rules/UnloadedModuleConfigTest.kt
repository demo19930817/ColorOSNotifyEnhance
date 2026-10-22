package com.fankes.coloros.notify.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The placeholder configuration stands in while the remote one is still being read. It must
 * replace nothing and yield everywhere: an empty pick set is "not known yet", not "nothing
 * picked", and a reboot's first notification regularly lands in that window.
 */
class UnloadedModuleConfigTest {

    @Test
    fun `placeholder config replaces nothing`() {
        val config = RuleStore.unloadedModuleConfig()
        assertFalse(config.rulesEnabled)
        assertFalse(config.panelIconReplacementEnabled)
        assertFalse(config.lockScreenCapsuleIconReplacementEnabled)
    }

    @Test
    fun `placeholder config picks no package for the host avatar`() {
        val config = RuleStore.unloadedModuleConfig()
        assertTrue(config.avatarPriorityPackages.isEmpty())
        assertTrue(config.hostAvatarPriorityEnabled)
    }
}
