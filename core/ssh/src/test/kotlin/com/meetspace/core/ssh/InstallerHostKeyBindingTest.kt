package com.meetspace.core.ssh

import com.meetspace.core.model.*
import org.junit.Assert.*
import org.junit.Test

class InstallerHostKeyBindingTest {
    private val checked = SshHostKey("ssh-ed25519", "test-key", "SHA256:test-key")

    @Test fun checkedPinIsAcceptedAndManualAccessKeepsExistingPinVerification() {
        requireInstallerHostKey(checked, checked)
        requireInstallerHostKey(checked, null)
    }

    @Test fun replacedPinFailsBeforeAuthentication() {
        try {
            requireInstallerHostKey(checked.copy(keyBase64 = "different-key"), checked)
            fail("A new stored pin must not receive access from the previous preflight")
        } catch (failure: SshFailure) { assertEquals(SshFailureKind.HOST_KEY_CHANGED, failure.kind) }
    }
}
