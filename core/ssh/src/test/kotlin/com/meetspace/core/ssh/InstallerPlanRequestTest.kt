package com.meetspace.core.ssh

import com.meetspace.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InstallerPlanRequestTest {
    @Test fun newImagePlanIgnoresStaleReleaseWithoutChangingOriginalOptions() {
        val original = InstallOptions("meet.example.com", "8.8.8.8", imageMode = InstallImageMode.IMAGES,
            imageRelease = "v0.6.0-prebuilt.previous")
        val request = installerPlanRequest(original).getValue("options").jsonObject
        assertEquals("images", request.getValue("imageMode").jsonPrimitive.content)
        assertEquals("latest", request.getValue("imageRelease").jsonPrimitive.content)
        assertEquals("v0.6.0-prebuilt.previous", original.imageRelease)
    }

    @Test fun sourceRequestOmitsReleaseAndPreservesPublicDeploymentSettings() {
        val options = InstallOptions("https://MEET.EXAMPLE.COM/", " 8.8.8.8 ", " owner@example.com ",
            smtp = InstallSmtpOptions("smtp.example.com", 465, "meet@example.com", "sender", "tls"),
            imageMode = InstallImageMode.SOURCE, imageRelease = "v0.6.0-prebuilt.previous", admin = InstallAdminOptions("owner", 9443))
        val request = installerPlanRequest(options).getValue("options").jsonObject
        assertEquals(buildJsonObject {
            put("domain", "meet.example.com"); put("publicIpv4", "8.8.8.8"); put("acmeEmail", "owner@example.com")
            put("imageMode", "source")
            put("admin", buildJsonObject { put("username", "owner"); put("httpsPort", 9443) })
            put("smtp", buildJsonObject {
                put("host", "smtp.example.com"); put("port", 465); put("from", "meet@example.com")
                put("username", "sender"); put("tlsMode", "tls")
            })
        }, request)
    }
}
