package com.meetspace.core.model

/** Account creation and session creation are separate backend operations. */
data class RegistrationResult(val user: User, val signedIn: Boolean, val signInFailure: String? = null)

enum class RegistrationField { NAME, EMAIL, PASSWORD, CONFIRMATION }
data class RegistrationValidationError(val field: RegistrationField, val message: String)

object RegistrationValidation {
    fun error(name: String, email: String, password: String, confirmation: String = password): RegistrationValidationError? {
        val displayName = name.trim()
        if (!validUnicode(displayName) || displayName.codePointCount(0, displayName.length) !in 1..100 || displayName.any(Char::isISOControl)) {
            return RegistrationValidationError(RegistrationField.NAME, "Введите имя от 1 до 100 символов.")
        }
        val address = email.trim()
        if (!validUnicode(address) || address.toByteArray(Charsets.UTF_8).size > 254 ||
            !address.matches(Regex("[^\\s@<>\"(),:;\\[\\]\\\\]+@[^\\s@<>\"(),:;\\[\\]\\\\]+")) || address.any(Char::isISOControl)) {
            return RegistrationValidationError(RegistrationField.EMAIL, "Проверьте адрес электронной почты.")
        }
        if (!validUnicode(password) || password.codePointCount(0, password.length) !in 8..128) {
            return RegistrationValidationError(RegistrationField.PASSWORD, "Пароль должен содержать от 8 до 128 символов.")
        }
        if (confirmation != password) {
            return RegistrationValidationError(RegistrationField.CONFIRMATION, "Пароли не совпадают. Повторите пароль.")
        }
        return null
    }

    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) return false
            } else if (character.isLowSurrogate()) return false
        }
        return true
    }
}
