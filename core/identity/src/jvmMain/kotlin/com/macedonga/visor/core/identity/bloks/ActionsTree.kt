package com.macedonga.visor.core.identity.bloks

import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.Json

/** Tree edits, kept per screen as overlays by parent node, and opening and closing screens and sheets. */
internal fun Registry.tree() {
    // ---- Lookups ----
    on("bk.action.bloks.Find", "bk.action.bloks.FindContainer") { container(at(0)) }
    on("bk.action.bloks.ChildAtIndex") {
        val n = node(0) ?: return@on null
        val list = screen?.childrenOf(n) ?: (n[BloksSession.primaryChildren(n.type)] as? List<*>).orEmpty()
        list.getOrElse(Interpreter.int(at(1))) { throw BloksException("child index ${at(1)} out of ${list.size}") }
    }
    on("bk.action.bloks.IndexOfChild") {
        val n = node(0) ?: return@on -1
        (screen?.childrenOf(n) ?: emptyList()).indexOfChild(at(1).toString())
    }
    on("bk.action.bloks.OneTimeBind") { at(0) }
    on("bk.action.bloks.Inflate") {
        val n = inflate(at(0))
        if (n != null) call(at(1), n) else call(at(2))
        null
    }
    on("bk.action.bloks.InflateSync") { inflate(at(0)) }

    // ---- Children: by container id, or in the parent of a child id ----
    on("bk.action.bloks.AppendChildren", "bk.action.bloks.AppendEmbeddedChildren") { edit(container(at(0))) { it + list(1) } }
    on("bk.action.bloks.PrependChildren", "bk.action.bloks.PrependEmbeddedChildren") { edit(container(at(0))) { list(1) + it } }
    on("bk.action.bloks.ReplaceChildren") { edit(container(at(0))) { list(1) } }
    on("bk.action.bloks.ReplaceEmbeddedChildren") {
        screen?.replaced?.put(at(0).toString(), list(1))
        null
    }
    on("bk.action.bloks.ReplaceChild", "bk.action.bloks.ReplaceEmbeddedChildV2") {
        val id = at(2).toString()
        edit(parent(id)) { kids -> kids.indexOfChild(id).let { i -> if (i < 0) kids else kids.take(i) + list(1) + kids.drop(i + 1) } }
    }
    on("bk.action.bloks.ReplaceChildrenAfter", "bk.action.bloks.ReplaceEmbeddedChildrenAfter") {
        val id = at(2).toString()
        edit(parent(id)) { kids -> kids.indexOfChild(id).let { i -> if (i < 0) kids else kids.take(i + 1) + list(1) } }
    }
    on("bk.action.bloks.InsertChildrenAfter", "bk.action.bloks.InsertEmbeddedChildrenAfter") {
        val id = at(2).toString()
        edit(parent(id)) { kids -> kids.indexOfChild(id).let { i -> if (i < 0) kids else kids.take(i + 1) + list(1) + kids.drop(i + 1) } }
    }
    on("bk.action.bloks.InsertChildrenBefore", "bk.action.bloks.InsertEmbeddedChildrenBefore") {
        val id = at(2).toString()
        edit(parent(id)) { kids -> kids.indexOfChild(id).let { i -> if (i < 0) kids else kids.take(i) + list(1) + kids.drop(i) } }
    }
    on("bk.action.bloks.RemoveChild") {
        val id = at(1).toString()
        edit(parent(id)) { kids -> kids.filterNot { (it as? Node)?.get(BloksSession.ID)?.toString() == id } }
    }
    on("bk.action.bloks.RemoveChildrenBetween") {
        val start = at(1).toString()
        val end = at(2).toString()
        edit(node(0)) { kids ->
            val a = kids.indexOfChild(start)
            val b = kids.indexOfChild(end)
            if (a < 0 || b < 0 || a > b) kids else kids.take(a + 1) + kids.drop(b)
        }
    }
    on("bk.action.component.SetAttr") {
        val n = node(0)
        val key = at(1) as? String
        if (n != null && !key.isNullOrEmpty()) screen?.attrs?.getOrPut(n) { HashMap() }?.put(key.codePointAt(0), at(2))
        null
    }
    // Visor does not measure Meta's layout: unmounted, as the app answers before its views exist.
    on("bk.action.component.GetHeight", "bk.action.component.GetWidth") { null }
    on("bk.action.component.GetHeight2", "bk.action.component.GetWidth2") { 0 }

    // ---- Screens ----
    on("bk.action.screen.Open") {
        val params = node(0)?.get(38) as? Map<*, *> ?: emptyMap<Any, Any>()
        val appId = params["__infra__app_id"] as String
        val container = params["__infra__container_config_id"] ?: 16542
        val query = BloksJson.encode(mapOf("params" to mapOf("__infra__container_config_id" to container)), boolsAsInts = false)
        session.request("screen $appId", { session.client.component(appId, query) }, { payload -> session.push(appId, payload, params) })
        null
    }
    on("bk.action.cds.OpenScreen", "bk.action.cds.PushScreen", "bk.action.cds.OpenCdsBottomSheet", "bk.action.cds.PushCdsBottomSheet") {
        val appId = node(0)!![35] as String
        val params = at(2) as? Map<*, *> ?: emptyMap<Any, Any>()
        val dismiss = (node(1)?.get(40) as? Lambda)?.takeIf { name.endsWith("OpenScreen") || name.endsWith("OpenCdsBottomSheet") }
        val isSheet = name.endsWith("BottomSheet")
        session.request("screen $appId", { session.client.screen(appId, flat(params)) }, { payload ->
            session.push(appId, payload, params) {
                onClose = dismiss
                sheet = isSheet
            }
        })
        null
    }
    on("bk.action.bloks.OpenScreen") {
        val appId = at(0) as String
        val params = at(1) as? Map<*, *> ?: emptyMap<Any, Any>()
        legacy(appId, params, name = null)
    }
    on("bk.action.bloks.OpenFullScreenV2", "bk.action.bloks.OpenFullScreenV4") {
        val data = node(0)!!
        val appId = data[35] as String
        legacy(appId, at(1) as? Map<*, *> ?: emptyMap<Any, Any>(), name = (data[45] ?: data[40])?.toString())
    }
    on("bk.action.bloks.CloseScreenV2", "bk.action.navigation.CloseScreen", "ig.action.navigation.CloseScreen") {
        val target = node(0)?.get(35)?.toString()
        if (target == null) {
            session.pop()
        } else {
            val index = session.screens.indexOfLast { it.name == target }
            if (index < 0) session.pop() else repeat(session.screens.size - index + (if (node(0)?.get(36) == true) 1 else 0)) { session.pop() }
        }
        null
    }
    on("bk.action.cds.CloseScreen") {
        val after = node(0)?.get(35)
        while (session.screens.isNotEmpty()) session.pop()
        call(after)
        null
    }
    on("bk.action.cds.DismissCdsBottomSheet") {
        val after = node(0)?.get(35)
        while (session.screens.lastOrNull()?.sheet == true) session.pop()
        call(after)
        null
    }
    on("bk.action.cds.PopCdsBottomSheet", "bk.action.screen.Close") {
        session.pop()
        null
    }
    on("bk.action.cds.internal.RemoveCdsBottomSheet") {
        val id = at(0)?.toString()
        session.screens.lastOrNull { it.params["__infra__screen_id"]?.toString() == id || it.params["INTERNAL_INFRA_screen_id"]?.toString() == id }
            ?.let { gone -> if (gone === session.screens.lastOrNull()) session.pop() else session.remove(gone) }
        null
    }
    on("bk.action.cds.UpdateBackButtonOverride") {
        screen?.backOverride = at(0) as? Lambda
        null
    }
    on("bk.action.cds.UpdateBottomSheet", "bk.action.cds.UpdateModalContainer", "bk.action.screen.EnableChainedNavigation", "bk.action.screen.DisableChainedNavigation") { null }
    // The app finds no container mode in every path of this build.
    on("bk.action.cds.internal.GetContainerMode") { null }
    // Updates to Meta's GraphQL store, and version markers: nothing here keeps either.
    on(
        "bk.action.pando.CreateRawTreeUpdater", "bk.action.pando.CreateTreeUpdater", "bk.action.pando.MaybeUpdateActiveFields",
        "bk.action.pando.PublishTreeUpdater", "bk.action.versioning.AndroidInternalMergeFix", "bk.action.versioning.AndroidSystemInsetsDataModule",
        "bk.action.versioning.FullscreenZoomableComponentFix", "bk.action.versioning.UnifiedStyleV2",
    ) { null }

    // ---- Sheets showing a payload already at hand ----
    on("bk.action.bloks.OpenBottomSheetV2", "bk.action.bloks.PushBottomSheetV2") {
        val instance = at(0) as? BloksSession.Instance ?: return@on null
        val root = instance.payload.tree ?: return@on null
        val content = root[35] as? Node ?: throw BloksException("Null content for BottomSheet")
        session.push(name, instance.payload.withTree(content), emptyMap<Any, Any>()) {
            sheet = true
            title = root[36] as? String
            onClose = root[38] as? Lambda
        }
        null
    }
    on("bk.action.bloks.DismissBottomSheet") {
        while (session.screens.lastOrNull()?.appId?.endsWith("BottomSheetV2") == true) session.pop()
        null
    }

    // ---- Links ----
    on("bk.action.navigation.OpenUrl", "ig.action.navigation.OpenUrlSupportsDfa") {
        val url = str(0)
        if (url.isNullOrEmpty()) session.pop() else session.host.openUrl(url)
        null
    }

    // ---- A date picker ----
    on("bk.action.cds.OpenDateTimePickerV2") {
        val onSet = at(0)
        val onCancel = at(7)
        val withTime = node(1)?.get(36) == "date_and_time" || node(1)?.get(36) == "time"
        val s = screen
        session.host.pickDate((at(3) as? Number)?.toLong(), withTime) { seconds ->
            session.post { if (seconds != null) call(onSet, seconds.toString()) else call(onCancel, s) }
        }
        null
    }

    // ---- Texts ----
    on("bk.action.text.GetText") { node(0)?.takeIf { it.type == UiBuilder.PLAIN_TEXT }?.let { it[41]?.toString() } }
    on("bk.action.text.IsTruncated") { false }
    on("bk.action.text.GetMeasuredLayout") { if ((at(0) as? List<*>).isNullOrEmpty()) null else linkedMapOf("lineCount" to 1, "overflowed" to false) }
    on("bk.action.textspan.GetCenterX", "bk.action.textspan.GetCenterY", "bk.action.textspan.GetHeight", "bk.action.textspan.GetWidth") { 0 }
    on("bk.action.string.FromProvider") {
        val provider = node(0) ?: return@on ""
        when (provider.type) {
            13319 -> DateFormat.getDateInstance().format(Date(((provider[36] as? Number)?.toLong() ?: 0L) * 1000))
            13950 -> provider[36]?.toString()?.ifBlank { "0" } ?: "0"
            else -> ""
        }
    }

    // ---- Lists, sliders, tooltips, videos, animations by keyframes: nothing in a login draws them ----
    on(
        "bk.action.tooltip.Show", "bk.action.tooltip.Hide", "bk.action.collection.SetIndex", "bk.action.collection.SetIndexById",
        "bk.action.collection.ScrollToIndexById", "bk.action.hcollection.SetOffset", "bk.action.vcollection.SetOffset",
        "bk.action.slider.SetPosition", "bk.action.keyframes.Pause", "bk.action.keyframes.Play", "bk.action.keyframes.Stop",
        "bk.action.keyframes.SeekToProgress", "bk.action.video.SendEventV2", "bk.action.video.SendEventV3", "bk.action.video.SetPositionV2",
    ) { null }
    on("bk.action.collection.GetVisibleCollectionItemAt") { LinkedHashMap<Any?, Any?>() }
    on("bk.action.hcollection.GetOffset", "bk.action.vcollection.GetOffset", "bk.action.keyframes.GetProgress", "bk.action.video.GetPositionV2") { 0 }
    on("bk.action.keyframes.IsPlaying", "bk.action.video.GetIsMutedV2", "bk.action.visibility_context.HasSeenBefore") { false }
    on("bk.action.video.GetPlaybackStateV3") { "unprepared" }
    on("bk.action.visibility_context.GetTimeSinceLastImpressionInMS") { 0L }
    on("bk.action.visibility_context.PercentVisible") { 1.0 }

    // ---- Consent flows and Instagram's own containers: not part of this app's login ----
    on("bk.action.privacy.consent.OpenIAWLink") {
        str(0)?.let(session.host::openUrl)
        call(at(1))
        null
    }
    on("bk.action.privacy.consent.CloseBottomSheet", "bk.action.privacy.consent.CloseScreen") {
        call(at(0))
        session.pop()
        null
    }
    on(
        "bk.action.privacy.consent.CloseFlow", "bk.action.privacy.consent.ShutdownExperience", "bk.action.privacy.consent.ShutdownExperienceWithError",
        "bk.action.privacy.consent.LaunchConsent", "bk.action.privacy.consent.OpenFlow", "bk.action.privacy.consent.OpenBottomSheet",
        "bk.action.privacy.consent.OpenScreen",
    ) {
        session.log("Meta opened a consent step ($name), which Visor does not draw")
        null
    }
    on(
        "ig.action.navigation.CloseModal", "ig.action.navigation.CloseModalWithResult", "ig.action.navigation.CloseToScreen",
        "ig.action.navigation.DismissBottomSheet", "ig.action.navigation.OpenBottomSheet", "ig.action.navigation.OpenDialog",
        "ig.action.navigation.PresentModalWithResult", "ig.action.navigation.UpdateTitle",
    ) { null }
}

/** The list argument at [i]; parse results (Instances) count as children, and so do nodes. */
private fun ActionCall.list(i: Int): List<Any?> = (at(i) as? List<*>).orEmpty()

private fun ActionCall.container(id: Any?): Node? = screen?.let { s -> s.payload.tree?.let { session.find(s, it, id.toString()) } }

/** A component tree written as Lispy maps, read back as nodes. */
private fun inflate(x: Any?): Node? = runCatching { Payload.convert(Json.parseToJsonElement(BloksJson.encode(x, boolsAsInts = false))) as? Node }.getOrNull()

private fun ActionCall.parent(childId: String): Node? = screen?.let { s -> s.payload.tree?.let { session.parentOf(s, it, childId) } }

/** Applies a change to a node's children, kept as the screen's overlay. A missing node changes nothing. */
private fun ActionCall.edit(node: Node?, change: (List<Any?>) -> List<Any?>): Any? {
    val s = screen ?: return null
    if (node == null) {
        session.trace("tree edit $name found no node")
        return null
    }
    s.children[node] = change(s.childrenOf(node))
    return null
}

private fun List<Any?>.indexOfChild(id: String): Int = indexOfFirst { (it as? Node)?.get(BloksSession.ID)?.toString() == id }

/** A legacy-stack screen: loaded with the root query, sent only its "params" entry. */
private fun ActionCall.legacy(appId: String, params: Map<*, *>, name: String?): Any? {
    val sent = BloksJson.encode(params["params"]?.let { mapOf("params" to it.toString()) } ?: emptyMap<String, String>(), boolsAsInts = false)
    session.request("screen $appId", { session.client.screen(appId, sent) }, { payload ->
        session.push(appId, payload, params) { this.name = name }
    })
    return null
}
