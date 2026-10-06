package dev.applesideload.signing

import dev.applesideload.core.Plist
import dev.applesideload.core.XmlPlist
import java.io.File
import java.security.MessageDigest

/**
 * The _CodeSignature/CodeResources file.
 *
 * Everything in the bundle that is not the executable is hashed here, and
 * the code directory hashes this file in turn, so a single changed image
 * invalidates the signature. The rules decide what is included and what may
 * be missing; they are Apple's defaults, because a bundle signed with
 * different rules is rejected on the device.
 */
object CodeResources {

    fun build(bundle: File, mainExecutable: String): ByteArray {
        val files = linkedMapOf<String, Plist>()
        val files2 = linkedMapOf<String, Plist>()

        bundle.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(bundle).path }
            .forEach { file ->
                val relative = file.relativeTo(bundle).path.replace(File.separatorChar, '/')
                if (shouldSkip(relative, mainExecutable)) return@forEach
                val bytes = file.readBytes()
                val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
                val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                val optional = relative.contains(".lproj/")

                files[relative] = if (optional) {
                    Plist.dict("hash" to Plist.Data(sha1), "optional" to Plist.Bool(true))
                } else {
                    Plist.Data(sha1)
                }
                files2[relative] = Plist.Dict(
                    linkedMapOf<String, Plist>(
                        "hash" to Plist.Data(sha1),
                        "hash2" to Plist.Data(sha256)
                    ).apply { if (optional) put("optional", Plist.Bool(true)) }
                )
            }

        return XmlPlist.write(
            Plist.dict(
                "files" to Plist.Dict(files),
                "files2" to Plist.Dict(files2),
                "rules" to rules(),
                "rules2" to rules2()
            )
        )
    }

    /**
     * What never appears in the resource list.
     *
     * The main executable is covered by the code directory itself, the
     * Info.plist has its own special slot, and the signature cannot hash
     * itself.
     */
    private fun shouldSkip(relative: String, mainExecutable: String): Boolean =
        relative == mainExecutable ||
            relative == "Info.plist" ||
            relative == "CodeResources" ||
            relative == "PkgInfo" ||
            relative.startsWith("_CodeSignature/") ||
            relative == "embedded.mobileprovision" ||
            relative.endsWith(".DS_Store")

    private fun rules(): Plist = Plist.dict(
        "^.*" to Plist.Bool(true),
        "^.*\\.lproj/" to Plist.dict(
            "optional" to Plist.Bool(true),
            "weight" to Plist.Real(1000.0)
        ),
        "^.*\\.lproj/locversion.plist$" to Plist.dict(
            "omit" to Plist.Bool(true),
            "weight" to Plist.Real(1100.0)
        ),
        "^Base\\.lproj/" to Plist.dict("weight" to Plist.Real(1010.0)),
        "^version.plist$" to Plist.Bool(true)
    )

    private fun rules2(): Plist = Plist.dict(
        ".*\\.dSYM($|/)" to Plist.dict("weight" to Plist.Real(11.0)),
        "^(.*/)?\\.DS_Store$" to Plist.dict(
            "omit" to Plist.Bool(true),
            "weight" to Plist.Real(2000.0)
        ),
        "^.*" to Plist.Bool(true),
        "^.*\\.lproj/" to Plist.dict(
            "optional" to Plist.Bool(true),
            "weight" to Plist.Real(1000.0)
        ),
        "^.*\\.lproj/locversion.plist$" to Plist.dict(
            "omit" to Plist.Bool(true),
            "weight" to Plist.Real(1100.0)
        ),
        "^Base\\.lproj/" to Plist.dict("weight" to Plist.Real(1010.0)),
        "^Info\\.plist$" to Plist.dict(
            "omit" to Plist.Bool(true),
            "weight" to Plist.Real(20.0)
        ),
        "^PkgInfo$" to Plist.dict(
            "omit" to Plist.Bool(true),
            "weight" to Plist.Real(20.0)
        ),
        "^embedded\\.provisionprofile$" to Plist.dict("weight" to Plist.Real(20.0)),
        "^version\\.plist$" to Plist.dict("weight" to Plist.Real(20.0))
    )
}
