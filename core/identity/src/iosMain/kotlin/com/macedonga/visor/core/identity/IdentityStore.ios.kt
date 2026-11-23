@file:OptIn(ExperimentalForeignApi::class)

package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.Monitor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Where bundles live on an iPhone: one Keychain item, readable after the first unlock so the link can come back in the
 * background, and never restored to another device. The chosen serial is no secret and sits in the user defaults.
 */
actual class IdentityStore(private val service: String = DEFAULT_SERVICE) {

    private val monitor = Monitor()

    actual fun all(): List<IdentityBundle> = monitor.locked { read()?.let { bundlesFromJson(it.decodeToString()) } ?: emptyList() }

    actual fun load(serial: String): IdentityBundle? = all().firstOrNull { it.serial == serial }

    actual fun chosen(): IdentityBundle? {
        val bundles = all()
        val current = currentSerial()
        return bundles.firstOrNull { it.serial == current } ?: bundles.maxByOrNull { it.createdAt }
    }

    actual fun currentSerial(): String? = NSUserDefaults.standardUserDefaults.stringForKey(currentKey)?.trim()?.takeIf { it.isNotEmpty() }

    actual fun choose(serial: String) {
        NSUserDefaults.standardUserDefaults.setObject(serial, currentKey)
    }

    actual fun ownerKeyPem(): ByteArray? = all().firstOrNull()?.privateKeyPem

    actual fun save(bundle: IdentityBundle) = monitor.locked {
        write(all().filter { it.serial != bundle.serial } + bundle)
    }

    actual fun update(serial: String, change: (IdentityBundle) -> IdentityBundle): Boolean = monitor.locked {
        val bundles = all()
        val old = bundles.firstOrNull { it.serial == serial } ?: return@locked false
        val new = change(old)
        if (new == old) return@locked false
        write(bundles.map { if (it.serial == serial) new else it })
        true
    }

    actual fun remove(serial: String) = monitor.locked {
        write(all().filter { it.serial != serial })
        if (currentSerial() == serial) NSUserDefaults.standardUserDefaults.removeObjectForKey(currentKey)
        Unit
    }

    private val currentKey get() = "$service.current"

    private fun read(): ByteArray? {
        val query = query()
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        try {
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@memScoped null
                check(status == errSecSuccess) { "the Keychain would not read the identities ($status)" }
                val data: CFDataRef = checkNotNull(result.value).reinterpret()
                try {
                    val out = ByteArray(CFDataGetLength(data).toInt())
                    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(data), out.size.convert()) }
                    out
                } finally {
                    CFRelease(data)
                }
            }
        } finally {
            CFRelease(query)
        }
    }

    private fun write(bundles: List<IdentityBundle>) {
        val query = query()
        try {
            SecItemDelete(query)
            if (bundles.isEmpty()) return
            val blob = bundlesToJson(bundles).encodeToByteArray()
            val data = blob.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), blob.size.convert()) }
            CFDictionarySetValue(query, kSecValueData, data)
            CFRelease(data)
            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemAdd(query, null)
            check(status == errSecSuccess) { "the Keychain would not keep the identities ($status)" }
        } finally {
            CFRelease(query)
        }
    }

    private fun query(): CFMutableDictionaryRef {
        val query = checkNotNull(
            CFDictionaryCreateMutable(null, 8.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
        )
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        val serviceName = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8)
        CFDictionarySetValue(query, kSecAttrService, serviceName)
        CFDictionarySetValue(query, kSecAttrAccount, account)
        CFRelease(serviceName)
        CFRelease(account)
        return query
    }

    companion object {
        const val DEFAULT_SERVICE = "com.macedonga.visor.identity"
        private const val ACCOUNT = "identities"
    }
}
