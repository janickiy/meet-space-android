package com.meetspace.app

import org.junit.Assert.*
import org.junit.Test

class RingtoneVolumeTest {
    @Test fun quietRingIsRaisedAndRestoredOnStop() {
        var level = 2
        val volume = RingtoneVolume({ level }, { 7 }, { true }) { level = it }
        volume.raise(); assertEquals(6, level)
        volume.raise(); volume.close(); assertEquals(2, level)
        volume.close(); assertEquals(2, level)
    }
    @Test fun manualChangeWinsOverRestoration() {
        var level = 2
        val volume = RingtoneVolume({ level }, { 7 }, { true }) { level = it }
        volume.raise(); level = 4; volume.close(); assertEquals(4, level)
    }
    @Test fun connectingAHeadsetDuringRingingStillRestoresTheUnchangedTemporaryBoost() {
        var level = 2; var headset = false
        val volume = RingtoneVolume({ level }, { 7 }, { !headset }, restoreAllowed = { true }) { level = it }
        volume.raise(); assertEquals(6, level)
        headset = true; volume.close(); assertEquals(2, level)
    }
    @Test fun headsetChangeDoesNotOverrideTheUsersNewVolume() {
        var level = 2; var headset = false
        val volume = RingtoneVolume({ level }, { 7 }, { !headset }, restoreAllowed = { true }) { level = it }
        volume.raise(); headset = true; level = 4
        volume.close(); assertEquals(4, level)
    }
    @Test fun quietSoundPolicyPreventsRestorationEvenIfReportedStreamLevelRemainsPositive() {
        var level = 2; var quiet = false; var writes = 0
        val volume = RingtoneVolume({ level }, { 7 }, { !quiet }, restoreAllowed = { !quiet }) { level = it; writes++ }
        volume.raise(); assertEquals(6, level)
        quiet = true; volume.close()
        assertEquals(6, level); assertEquals(1, writes)
    }
    @Test fun silentDndHeadsetAndFixedVolumeAreNotRaised() {
        var writes = 0
        RingtoneVolume({ 2 }, { 7 }, { false }) { writes++ }.apply { raise(); close() }
        RingtoneVolume({ 0 }, { 7 }, { true }) { writes++ }.apply { raise(); close() }
        RingtoneVolume({ 2 }, { 0 }, { true }) { writes++ }.apply { raise(); close() }
        assertEquals(0, writes)
    }
    @Test fun loudRingIsNeverLowered() {
        var level = 7
        RingtoneVolume({ level }, { 7 }, { true }) { level = it }.apply { raise(); close() }
        assertEquals(7, level)
    }
    @Test fun becomingSilentWhileRingingDoesNotUnmuteOnStop() {
        var level = 2; var allowed = true
        val volume = RingtoneVolume({ level }, { 7 }, { allowed }) { level = it }
        volume.raise(); allowed = false; level = 0; volume.close(); assertEquals(0, level)
    }
    @Test fun rejectedSystemVolumeChangeDoesNotCrashOrRestoreUnappliedLevel() {
        var writes = 0
        val volume = RingtoneVolume({ 2 }, { 7 }, { true }) { writes++; throw SecurityException() }
        volume.raise(); volume.close(); assertEquals(1, writes)
    }
}
