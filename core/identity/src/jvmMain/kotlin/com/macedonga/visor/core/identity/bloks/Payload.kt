package com.macedonga.visor.core.identity.bloks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A component: its type (a code point in the payload) and its attributes by attribute id. */
class Node(val type: Int, val attrs: Map<Int, Any?>) {
    operator fun get(attr: Int): Any? = attrs[attr]

    override fun toString() = "Node($type)"
}

/** A script attribute or table entry, parsed on first use. */
class Script(val source: String) {
    val expr: Expr by lazy { Lispy.parse(source) }
    override fun toString() = "Script(${source.length})"
}

/** An async component: fetched on its own, its tree replaces the children of [target]. */
class ComponentQuery(val target: String?, val appId: Script?, val params: Script?, val clientParams: Script?)

/** A local ("ls") or global ("gs") variable the payload declares. A global in mode "p" is reset when declared. */
class Variable(val id: String, val type: String, val initial: Any?, val initialScript: Script?, val key: String?, val mode: String?)

/** One `bloks_payload`: a screen, an action, or a payload embedded in another. */
class Payload(
    val tree: Node?,
    val action: Script?,
    val variables: List<Variable>,
    val scripts: Map<String, Script>,
    val embedded: Map<String, Payload>,
    val props: Map<String, String>,
    /** `values`: expressions read by `bloks.GetValue`, by id. */
    val values: Map<String, Script> = emptyMap(),
    /** `ct`: constants read by `const.Get`, by name. */
    val constants: Map<String, Any?> = emptyMap(),
    /** `templates`: trees `template.Make` instantiates, by id. */
    val templates: Map<String, Node> = emptyMap(),
    val componentQueries: List<ComponentQuery> = emptyList(),
) {
    /** The same payload drawn from another root, as a bottom sheet shows the content node of its parse result. */
    fun withTree(root: Node?): Payload = Payload(root, null, variables, scripts, embedded, props, values, constants, templates, componentQueries)

    companion object {
        /** `{"layout":{"bloks_payload":{...}}}`, the JSON string a Bloks query answers with. */
        fun parse(bundle: String): Payload = of(Json.parseToJsonElement(bundle).jsonObject)

        fun of(layoutHolder: JsonObject): Payload {
            val p = layoutHolder["layout"]?.jsonObject?.get("bloks_payload")?.jsonObject ?: throw BloksException("answer has no layout.bloks_payload")
            return Payload(
                tree = p["tree"]?.let { convert(it) as? Node },
                action = (p["action"] as? JsonPrimitive)?.contentOrNull?.let(::Script),
                variables = (p["data"] as? JsonArray).orEmpty().map { v ->
                    val o = v.jsonObject
                    val d = o["data"] as? JsonObject
                    Variable(
                        id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: throw BloksException("variable without id"),
                        type = o["type"]?.jsonPrimitive?.content.orEmpty(),
                        initial = d?.get("initial")?.let(::convert),
                        initialScript = (d?.get("initial_lispy") as? JsonPrimitive)?.contentOrNull?.let(::Script),
                        key = (d?.get("key") as? JsonPrimitive)?.contentOrNull,
                        mode = (d?.get("mode") as? JsonPrimitive)?.contentOrNull,
                    )
                },
                scripts = (p["ft"] as? JsonObject).orEmpty().mapValues { Script(it.value.jsonPrimitive.content) },
                embedded = (p["embedded_payloads"] as? JsonArray).orEmpty().associate { e ->
                    val o = e.jsonObject
                    val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: throw BloksException("embedded payload without id")
                    id to of(o["payload"] as? JsonObject ?: throw BloksException("embedded payload without payload"))
                },
                props = (p["props"] as? JsonArray).orEmpty().associate { e ->
                    val o = e.jsonObject
                    val name = (o["name"] as? JsonPrimitive)?.contentOrNull
                    val id = (o["id"] as? JsonPrimitive)?.contentOrNull
                    if (name == null || id == null) throw BloksException("prop without name or id")
                    name to id
                },
                values = (p["values"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val expression = (o["expression"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    id to Script(expression)
                }.toMap(),
                constants = when (val ct = p["ct"]) {
                    is JsonObject -> ct.mapValues { convert(it.value) }
                    is JsonArray -> ct.mapNotNull { e ->
                        val o = e as? JsonObject ?: return@mapNotNull null
                        val name = ((o["name"] ?: o["id"]) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        name to o["value"]?.let(::convert)
                    }.toMap()
                    else -> emptyMap()
                },
                templates = (p["templates"] as? JsonObject).orEmpty().mapNotNull { (id, tree) -> (convert(tree) as? Node)?.let { id to it } }.toMap(),
                componentQueries = (p["component_queries"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    fun script(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.let(::Script)
                    ComponentQuery(
                        target = ((o["targets"] as? JsonObject)?.get("component") as? JsonPrimitive)?.contentOrNull,
                        appId = script("app_id_expr"),
                        params = script("params"),
                        clientParams = script("client_params"),
                    )
                },
            )
        }

        /** Payload JSON to Kotlin: a one-key object keyed by a code point past U+3000 is a component. */
        fun convert(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content.let { if (Lispy.isScript(it)) Script(it) else it }
                else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull
            }
            is JsonArray -> element.map(::convert)
            is JsonObject -> {
                val only = element.keys.singleOrNull()
                if (only != null && only.length == 1 && only[0].code >= 0x3000) {
                    val attrs = element[only]!!.jsonObject.entries.associate { (k, v) -> attrId(k) to convert(v) }
                    Node(only[0].code, attrs)
                } else {
                    element.mapValues { convert(it.value) }
                }
            }
        }

        private fun attrId(key: String): Int = if (key.length == 1) key[0].code else key.toIntOrNull() ?: -1
    }
}
