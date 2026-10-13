package com.meetspace.core.ssh

import android.content.Context
import java.io.File

/** Debug instrumentation only; no intent, network or release-build entry point. */
object InstallerFixtureFactory {
    fun create(context: Context, directory: File): SshInstallerRepository {
        require(directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        val files = InstallerBundle.names.associateWith { name -> File(directory, name).inputStream().use { stream ->
            val data = ByteArray(256 * 1024 + 1); val count = stream.read(data); require(count in 1..256 * 1024); data.copyOf(count)
        } }
        return AndroidSshInstallerRepository(context.applicationContext, InstallerBundle(files, allowTestProfile = true))
    }
}
