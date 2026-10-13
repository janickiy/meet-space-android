package com.meetspace.core.data

import com.meetspace.core.model.RegistrationField
import com.meetspace.core.model.RegistrationValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationValidationTest {
    @Test fun `unicode character limits match backend rune counts`() {
        assertNull(RegistrationValidation.error("😀".repeat(100), "test@example.com", "😀".repeat(8)))
        assertEquals(RegistrationField.NAME, RegistrationValidation.error("😀".repeat(101), "test@example.com", "synthetic-password")!!.field)
        assertEquals(RegistrationField.PASSWORD, RegistrationValidation.error("Test", "test@example.com", "😀".repeat(129))!!.field)
        assertEquals(RegistrationField.NAME, RegistrationValidation.error("\uD800", "test@example.com", "synthetic-password")!!.field)
    }
    @Test fun `email byte limit and confirmation are enforced`() {
        assertEquals(RegistrationField.EMAIL, RegistrationValidation.error("Test", "é".repeat(123) + "@example.com", "synthetic-password")!!.field)
        assertEquals(RegistrationField.CONFIRMATION, RegistrationValidation.error("Test", "test@example.com", "synthetic-password", "different")!!.field)
        assertNull(RegistrationValidation.error(" Test ", " test@example.com ", "synthetic-password", "synthetic-password"))
    }
}
