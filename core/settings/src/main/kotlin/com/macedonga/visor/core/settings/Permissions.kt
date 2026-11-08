package com.macedonga.visor.core.settings

import com.google.flatbuffers.FlexBuffersBuilder
import java.nio.ByteBuffer

/**
 * The glasses' grants to phone apps, as the Meta app keeps them in RLDrive: namespace [PERMISSIONS_NAMESPACE], type
 * `permission_manifest`, uri `<package>_<permission>`, a FlexBuffers map of `app_package`, `permission` and
 * `user_consent` (`ALWAYS`, `ONCE` or `NEVER`). The Display's permission bridge answers toolkit apps from them.
 */
const val PERMISSIONS_NAMESPACE = "ecosystem_permissions"

/** A grant of [permission], such as `android.permission.DEV_MODE`, to [app], for [readNamespace]'s push. */
fun permissionObject(app: String, permission: String, consent: String): PushObject {
    val b = FlexBuffersBuilder(ByteBuffer.allocate(256), FlexBuffersBuilder.BUILDER_FLAG_SHARE_KEYS)
    val start = b.startMap()
    b.putString("app_package", app)
    b.putString("permission", permission)
    b.putString("user_consent", consent)
    b.endMap(null, start)
    val out = b.finish()
    return PushObject("${app}_$permission", "permission_manifest", ByteArray(out.remaining()).also { out.get(it) })
}
