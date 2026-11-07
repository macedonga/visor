package com.macedonga.visor.outside

import android.content.Context
import android.content.pm.PackageManager
import com.macedonga.visor.kit.protocol.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What the person let one app do, and the certificate the app was signed with then. */
data class KitGrant(val certificate: String, val scopes: Set<Scope>)

/**
 * The scopes each app holds through the VDK, by package. A grant belongs to the signing certificate the app had when
 * the person granted it: another app installed later under the same package holds nothing.
 */
@Singleton
class KitGrants @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("kit_grants", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(read())
    val all: StateFlow<Map<String, KitGrant>> = _all.asStateFlow()

    /** The scopes [app] holds now; none when its certificate changed since the grant. */
    fun scopesOf(app: String): Set<Scope> {
        val grant = _all.value[app] ?: return emptySet()
        return if (certificateOf(app) == grant.certificate) grant.scopes else emptySet()
    }

    /** Sets the scopes of [app]. False when it gets scopes but Visor cannot see it; taking all of them always works. */
    @Synchronized
    fun set(app: String, scopes: Set<Scope>): Boolean {
        if (scopes.isEmpty()) {
            _all.value -= app
        } else {
            val certificate = certificateOf(app) ?: return false
            _all.value += app to KitGrant(certificate, scopes)
        }
        write()
        return true
    }

    /** The package behind [uid]; the first one for a shared uid. */
    fun packageOf(uid: Int): String? = context.packageManager.getPackagesForUid(uid)?.minOrNull()

    /** The app's name, as the launcher shows it. */
    fun labelOf(app: String): String = try {
        context.packageManager.run { getApplicationLabel(getApplicationInfo(app, 0)).toString() }
    } catch (e: PackageManager.NameNotFoundException) {
        app
    }

    /** SHA-256 of the certificates that sign [app] now, or null when Visor cannot see the app. */
    fun certificateOf(app: String): String? = try {
        val signing = context.packageManager.getPackageInfo(app, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        signing.apkContentsSigners.map { cert -> digest.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun read(): Map<String, KitGrant> {
        val text = prefs.getString(KEY, null) ?: return emptyMap()
        val apps = JSONObject(text)
        return apps.keys().asSequence().associateWith { app ->
            val grant = apps.getJSONObject(app)
            val scopes = grant.getJSONArray(SCOPES)
            KitGrant(grant.getString(CERTIFICATE), (0 until scopes.length()).mapTo(LinkedHashSet()) { Scope(scopes.getString(it)) })
        }
    }

    private fun write() {
        val apps = JSONObject()
        for ((app, grant) in _all.value) {
            apps.put(app, JSONObject().put(CERTIFICATE, grant.certificate).put(SCOPES, JSONArray(grant.scopes.map { it.id })))
        }
        prefs.edit().putString(KEY, apps.toString()).apply()
    }

    private companion object {
        const val KEY = "apps"
        const val CERTIFICATE = "certificate"
        const val SCOPES = "scopes"
    }
}
