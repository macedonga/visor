package com.macedonga.visor.core.identity.bloks

import java.net.URI
import java.net.URLDecoder
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Collections
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.truncate

/** A script function: its body, the args bound in front of a call's, and the context it resolves names in. */
class Lambda(val body: Expr, val bound: List<Any?>, val ctx: Ctx)

/** `bk.action.ref.Make`: a mutable cell. */
class Ref(var value: Any?)

class BloksException(message: String) : Exception(message)

/** Where a script resolves its `#id` scripts and sends everything that is not a pure built-in. */
interface Ctx {
    fun script(id: String): Lambda

    fun extension(interpreter: Interpreter, name: String, args: List<Any?>): Any?
}

private class PatternMark(val value: Any?, val body: Expr)

private class DefaultMark(val body: Expr)

private typealias Builtin = Interpreter.(List<Any?>) -> Any?

/** Bloks script evaluator: one frame per lambda call, lazy special forms, pure built-ins in [BUILTINS], the rest to the context. */
class Interpreter {
    private val frames = ArrayList<MutableList<Any?>>()

    /** How many times each built-in ran since the last [drainCounts], for the trace. */
    private val counts = HashMap<String, Int>()

    fun drainCounts(): Map<String, Int> = HashMap(counts).also { counts.clear() }

    /** A top-level script with the given frame. */
    fun run(expr: Expr, ctx: Ctx, args: List<Any?> = emptyList()): Any? = framed(args.toMutableList()) { eval(expr, ctx) }

    fun call(fn: Any?, args: List<Any?>): Any? {
        val f = fn as? Lambda ?: throw BloksException("not a function: ${kind(fn)}")
        return framed((f.bound + args).toMutableList()) { eval(f.body, f.ctx) }
    }

    private inline fun <T> framed(frame: MutableList<Any?>, block: () -> T): T {
        frames.add(frame)
        try {
            return block()
        } finally {
            frames.removeAt(frames.lastIndex)
        }
    }

    internal val frame: MutableList<Any?> get() = frames.last()

    fun eval(e: Expr, ctx: Ctx): Any? = when (e) {
        is Lit -> e.value
        is Call -> if (e.local != null) call(ctx.script(e.local), e.args.map { eval(it, ctx) }) else special(e, ctx)
    }

    private fun special(c: Call, ctx: Ctx): Any? {
        val a = c.args
        fun arg(i: Int): Any? = if (i < a.size) eval(a[i], ctx) else null
        val name = Functions.name(c.fn)
        return when (name) {
            "bk.action.core.FuncConst" -> Lambda(a[0], emptyList(), ctx)
            "bk.action.core.Apply" -> {
                val fn = arg(0)
                call(fn, a.drop(1).map { eval(it, ctx) })
            }
            "bk.action.core.GetArg" -> frame.getOrNull(int(arg(0)))
            "bk.action.core.SetArg" -> {
                val i = int(arg(0))
                val v = arg(1)
                while (frame.size <= i) frame.add(null)
                frame[i] = v
                null
            }
            "bk.action.core.TakeLast" -> {
                var last: Any? = null
                for (x in a) last = eval(x, ctx)
                last
            }
            "bk.action.core.If" -> if (truthy(arg(0))) arg(1) else arg(2)
            "bk.action.core.Coalesce" -> a.firstNotNullOfOrNull { eval(it, ctx) }
            "bk.action.core.Pattern" -> PatternMark(arg(0), body(a[1]))
            "bk.action.core.Default" -> DefaultMark(body(a[0]))
            "bk.action.core.Match" -> {
                val value = arg(0)
                val hit = (arg(1) as? List<*>).orEmpty().filterIsInstance<PatternMark>().firstOrNull { equal(it.value, value) }
                if (hit != null) eval(hit.body, ctx) else eval((arg(2) as? DefaultMark ?: throw BloksException("Match without default")).body, ctx)
            }
            "bk.action.core.While" -> {
                val cond = body(a[0])
                val loop = body(a[1])
                while (truthy(eval(cond, ctx))) eval(loop, ctx)
                null
            }
            "bk.action.mins.ArgCount" -> frame.size
            "bk.action.bloks.debug.Breakpoint" -> null
            // Folded to plain values when the app parses a script.
            "bk.action.i64.Const" -> (arg(0) as? Number)?.toLong()
            "bk.action.i32.Const" -> (arg(0) as? Number)?.toInt()
            "bk.action.f32.Const", "bk.action.f64.Const" -> (arg(0) as? Number)?.let { norm(it.toDouble()) }
            "bk.action.bool.Const" -> truthy(arg(0))
            else -> {
                val args = a.map { eval(it, ctx) }
                val builtin = BUILTINS[name] ?: return ctx.extension(this, name, args)
                try {
                    builtin(args)
                } catch (error: BloksException) {
                    throw error
                } catch (error: Exception) {
                    // A built-in's own failure (a cast, an index): say which function it was.
                    throw BloksException("$name: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }.also { counts.merge(name, 1, Int::plus) }
    }

    /** A Pattern's or Default's body is the FuncConst's own expression, run in the current frame. */
    private fun body(e: Expr): Expr =
        if (e is Call && e.local == null && Functions.name(e.fn) == "bk.action.core.FuncConst") e.args[0] else e

    companion object {
        private val BUILTINS: Map<String, Builtin> = HashMap<String, Builtin>().apply {
            fun on(vararg names: String, f: Builtin) = names.forEach { put(it, f) }

            // ---- Arrays ----
            on("bk.action.array.Make") { ArrayList(it) }
            on("bk.action.array.Get") { v ->
                when (val c = v.getOrNull(0)) {
                    is List<*> -> c.getOrElse(int(v.getOrNull(1))) { throw BloksException("index ${v.getOrNull(1)} out of ${c.size}") }
                    is Map<*, *> -> if (c.containsKey(v.getOrNull(1))) c[v.getOrNull(1)] else throw BloksException("key not found: ${v.getOrNull(1)}")
                    is String -> c[int(v.getOrNull(1))].code
                    else -> throw BloksException("array.Get on ${kind(c)}")
                }
            }
            on("bk.action.array.Length", "bk.action.string.Length") { v ->
                when (val c = v.getOrNull(0)) {
                    is String -> c.length
                    is List<*> -> c.size
                    is Map<*, *> -> c.size
                    else -> throw BloksException("length of ${kind(c)}")
                }
            }
            on("bk.action.array.Map") { v ->
                val list = v.getOrNull(0) as? List<*> ?: return@on Collections.EMPTY_LIST
                list.mapIndexed { i, x -> call(v.getOrNull(1), listOf(i, x)) }.toMutableList()
            }
            on("bk.action.array.Filter") { v -> (v.getOrNull(0) as? List<*>).orEmpty().filter { strict(call(v.getOrNull(1), listOf(it))) }.toMutableList() }
            on("bk.action.array.FindIndex") { v -> (v.getOrNull(0) as? List<*>).orEmpty().indexOfFirst { strict(call(v.getOrNull(1), listOf(it))) } }
            on("bk.action.array.Reduce") { v ->
                var acc = v.getOrNull(2)
                (v.getOrNull(0) as? List<*>).orEmpty().forEachIndexed { i, x -> acc = call(v.getOrNull(1), listOf(acc, x, i)) }
                acc
            }
            on("bk.action.array.Concat") { v -> v.flatMap { (it as? List<*>).orEmpty() }.toMutableList() }
            on("bk.action.array.Append") { v -> ((v.getOrNull(0) as? List<*>).orEmpty() + listOf(v.getOrNull(1))).toMutableList() }
            on("bk.action.array.Slice") { v ->
                val l = v[0] as List<*>
                runCatching { ArrayList(l.subList(int(v[1]), (v.getOrNull(2) as? Number)?.toInt() ?: l.size)) }.getOrNull()
            }
            on("bk.action.array.Contains") { v ->
                when (val c = v.getOrNull(0)) {
                    is String -> c.contains(v.getOrNull(1).toString())
                    is List<*> -> c.any { equal(it, v.getOrNull(1)) }
                    is Map<*, *> -> c.values.any { equal(it, v.getOrNull(1)) }
                    else -> false
                }
            }
            // Writes in place; an immutable target is skipped (Put) or an error (PutByVal).
            on("bk.action.array.Put") { v -> put(v[0], v.getOrNull(1), v.getOrNull(2), quiet = true) }
            on("bk.action.mins.PutByVal") { v -> put(v[0], v.getOrNull(1), v.getOrNull(2), quiet = false) }
            on("bk.action.array.Remove") { v ->
                @Suppress("UNCHECKED_CAST")
                val list = v[0] as? MutableList<Any?> ?: throw BloksException("array.Remove on ${kind(v[0])}")
                val i = index(v.getOrNull(1))
                if (i < list.size) list.removeAt(i)
                null
            }
            on("bk.action.array.Update") { v ->
                val indices = v.getOrNull(1) as? List<*> ?: throw BloksException("array.Update indices")
                val values = v.getOrNull(2) as? List<*> ?: throw BloksException("array.Update values")
                if (indices.size != values.size) throw BloksException("Malformatted Lispy: array.Update")
                // The app stops quietly at the first bad write; earlier writes stay.
                runCatching { indices.forEachIndexed { i, k -> put(v[0], k, values[i], quiet = false) } }
                null
            }
            on("bk.action.array.SortedArray") { v ->
                val out = ArrayList(v[0] as Collection<*>)
                out.sortWith { x, y -> sign((call(v[1], listOf(x, y)) as? Number)?.toDouble() ?: 0.0).toInt() }
                out
            }
            on("bk.action.array.Max") { v -> (v.getOrNull(0) as? List<*>).orEmpty().filterIsInstance<Number>().maxOfOrNull { it.toDouble() } }
            on("bk.action.array.Min") { v -> (v.getOrNull(0) as? List<*>).orEmpty().filterIsInstance<Number>().minOfOrNull { it.toDouble() } }

            // ---- Maps ----
            on("bk.action.map.Make") { v ->
                val keys = v[0] as List<*>
                val values = v[1] as List<*>
                if (keys.size != values.size) throw BloksException("Malformatted Lispy: map.Make")
                LinkedHashMap<Any?, Any?>().apply { keys.forEachIndexed { i, k -> put(k, values[i]) } }
            }
            on("bk.action.map.Get") { v ->
                when (val c = v.getOrNull(0)) {
                    is Map<*, *> -> c[v.getOrNull(1)]
                    is List<*> -> c.getOrNull(int(v.getOrNull(1)))
                    else -> throw BloksException("map.Get on ${kind(c)}")
                }
            }
            on("bk.action.map.Merge") { v -> LinkedHashMap<Any?, Any?>().apply { v.forEach { m -> (m as? Map<*, *>)?.let { putAll(it) } } } }
            on("bk.action.map.Keys") { v -> (v[0] as Map<*, *>).keys.toMutableList() }
            on("bk.action.map.Values") { v -> (v[0] as Map<*, *>).values.toMutableList() }
            on("bk.action.map.Remove") { v ->
                @Suppress("UNCHECKED_CAST")
                runCatching { (v[0] as MutableMap<Any?, Any?>).remove(v.getOrNull(1)) }
                null
            }
            on("bk.action.map.Update") { v ->
                @Suppress("UNCHECKED_CAST")
                (v[0] as MutableMap<Any?, Any?>).putAll(v[1] as Map<*, *>)
                null
            }
            on("bk.action.map.Filter") { v ->
                LinkedHashMap<Any?, Any?>().apply { (v[0] as Map<*, *>).forEach { (k, x) -> if (strict(call(v.getOrNull(1), listOf(k, x)))) put(k, x) } }
            }

            // ---- mins ----
            on("bk.action.mins.CallRuntime") { v -> runtime(int(v[0]), v.drop(1)) }
            on("bk.action.mins.GetByValOr") { v ->
                when (val c = v.getOrNull(0)) {
                    is Map<*, *> -> if (c.containsKey(v.getOrNull(1))) c[v.getOrNull(1)] else v.getOrNull(2)
                    is List<*> -> c.getOrElse(int(v.getOrNull(1))) { v.getOrNull(2) }
                    else -> v.getOrNull(2)
                }
            }
            on("bk.action.mins.InByVal") { v ->
                when (val c = v.getOrNull(1)) {
                    is Map<*, *> -> c.containsKey(v.getOrNull(0))
                    is List<*> -> int(v.getOrNull(0)) in c.indices
                    else -> false
                }
            }
            on("bk.action.mins.TypeOf") { v -> typeOf(v.getOrNull(0)) }
            on("bk.action.mins.AssertType") { v ->
                val want = int(v.getOrNull(1))
                val t = typeOf(v.getOrNull(0))
                if (t == want || (want == 100 && (t == 3 || t == 4))) v.getOrNull(0) else throw BloksException("type $t is not $want")
            }
            on("bk.action.mins.ToBool") { v -> truthy(v.getOrNull(0)) }
            on("bk.action.mins.ToInt32", "bk.action.mins.Trunc", "bk.action.i32.Convert") { v -> toI32(v.getOrNull(0)) }
            on("bk.action.i64.Convert") { v -> toI64(v.getOrNull(0)) }
            on("bk.action.f32.Convert") { v -> norm(num(v.getOrNull(0))) }
            on("bk.action.mins.Raise") { v -> throw BloksException("raised: ${v.getOrNull(0)}") }
            on("bk.action.mins.BinAsr") { v -> toI32(v[0]) shr (toI32(v[1]) and 31) }
            on("bk.action.mins.BinNot") { v -> toI32(v[0]).inv() }
            on("bk.action.mins.BinShl") { v -> toI32(v[0]) shl (toI32(v[1]) and 31) }
            on("bk.action.mins.BinShr") { v -> norm(((toI32(v[0]) ushr (toI32(v[1]) and 31)).toLong() and 0xFFFFFFFFL).toDouble()) }
            on("bk.action.mins.BinXor") { v -> toI32(v[0]) xor toI32(v[1]) }
            on("bk.action.mins.IMul") { v -> toI32(v[0]) * toI32(v[1]) }
            on("bk.action.mins.Int64And") { v -> toI64(v[0]) and toI64(v[1]) }
            on("bk.action.mins.Int64Or") { v -> toI64(v[0]) or toI64(v[1]) }
            on("bk.action.mins.Int64Xor") { v -> toI64(v[0]) xor toI64(v[1]) }
            on("bk.action.mins.Int64Not") { v -> toI64(v[0]).inv() }
            on("bk.action.mins.Int64Shl") { v -> toI64(v[0]) shl (toI64(v[1]) and 63).toInt() }
            on("bk.action.mins.Int64Asr") { v -> toI64(v[0]) shr (toI64(v[1]) and 63).toInt() }
            on("bk.action.mins.Int64Shr") { v -> toI64(v[0]) ushr (toI64(v[1]) and 63).toInt() }
            on("bk.action.mins.Int64Mod") { v ->
                val d = toI64(v[1])
                if (d == 0L) throw BloksException("Division by Zero")
                toI64(v[0]) % d
            }
            on("bk.action.mins.ContainerClone") { v ->
                when (val c = v[0]) {
                    is List<*> -> ArrayList(c)
                    is Map<*, *> -> LinkedHashMap(c)
                    else -> throw BloksException("ContainerClone of ${kind(c)}")
                }
            }
            on("bk.action.mins.ImmutableClone") { v ->
                when (val c = v.getOrNull(0)) {
                    is List<*> -> Collections.unmodifiableList(ArrayList(c))
                    is Map<*, *> -> Collections.unmodifiableMap(LinkedHashMap(c))
                    else -> c
                }
            }
            on("bk.action.mins.DelByVal") { v ->
                @Suppress("UNCHECKED_CAST")
                (v[0] as? MutableMap<Any?, Any?> ?: throw BloksException("DelByVal on ${kind(v[0])}")).remove(v.getOrNull(1))
                null
            }
            on("bk.action.mins.VectorAppend") { v ->
                @Suppress("UNCHECKED_CAST")
                (v[0] as? MutableList<Any?> ?: throw BloksException("VectorAppend on ${kind(v[0])}")).add(v.getOrNull(1))
                null
            }
            // Named in the app's table but bound to nothing in 294: they answer null there too.
            on(
                "bk.action.mins.Chr16", "bk.action.mins.EqArgCount", "bk.action.mins.GetArguments", "bk.action.mins.ToUint32",
                "bk.action.mins.VectorResize", "bk.action.splice.Apply", "bk.action.splice.Get", "bk.action.bloks.Timestamp",
                "bk.action.bloks.TimestampOfLastTouchUp",
            ) { null }

            // ---- Core helpers ----
            on("bk.action.core.AsNonnull") { v -> v.getOrNull(0) ?: throw BloksException("AsNonnull on null") }
            on("null") { v -> v.getOrNull(0) == null }
            on("bk.action.core.Let") { v -> v.getOrNull(0)?.let { call(v.getOrNull(1), listOf(it)) } }
            on("bk.action.function.BindWithArrayV2") { v ->
                val f = v.getOrNull(0) as? Lambda ?: throw BloksException("bind on ${kind(v.getOrNull(0))}")
                val list = v.getOrNull(1) as? List<*>
                if (list.isNullOrEmpty()) f else Lambda(f.body, list, f.ctx)
            }
            on("data.Get") { v -> (v.getOrNull(0) as? Node)?.attrs?.get(int(v[1])) }
            on("pando.As") { v -> (v.getOrNull(0) as? Map<*, *>)?.takeIf { it[v[1]] != null } }
            on("pando.Read") { v -> pandoWalk(v, require = false) }
            on("pando.Require") { v -> pandoWalk(v, require = true) }
            on("bk.action.react.NormalizeChildren") { v ->
                when (val c = v.getOrNull(0)) {
                    null -> Collections.EMPTY_LIST
                    is List<*> -> if (c.all(::isChild)) c else c.filter(::isChild)
                    else -> if (isChild(c)) Collections.singletonList(c) else Collections.EMPTY_LIST
                }
            }

            // ---- Strings ----
            on("bk.action.string.Concat") { v ->
                val x = v.getOrNull(0)
                val y = v.getOrNull(1)
                if (x is String && y is String) x + y else arith(x, y, Double::plus, Long::plus)
            }
            on("bk.action.string.ValueOfNumber") { v -> valueOfNumber(v.getOrNull(0)) }
            on("bk.action.string.ValueOfNumberInBase") { v ->
                val radix = (v[1] as Number).toInt()
                if (radix !in 2..36) throw BloksException("radix parameter of string.ValueOfNumberInBase must be between 2 and 36")
                java.lang.Long.toString((v[0] as Number).toLong(), radix)
            }
            on("bk.action.string.JsonEncode", "bk.action.string.JsonEncodeV2") { v -> BloksJson.encode(v.getOrNull(0), boolsAsInts = true) }
            on("bk.action.string.JsonEncodeV3") { v -> BloksJson.encode(v.getOrNull(0), boolsAsInts = false) }
            on("bk.action.string.JsonDecode", "bk.action.string.JsonDecodeArray") { v -> (v.getOrNull(0) as? String)?.let(BloksJson::decode) }
            on("bk.action.string.Join") { v -> (v[1] as List<*>).joinToString(v[0] as String) { it as String } }
            on("bk.action.string.Contains") { v -> (v[0] as String).contains(v[1] as String) }
            on("bk.action.string.StartsWith") { v -> (v[0] as String).startsWith(v[1] as String) }
            on("bk.action.string.ToLowerCase") { v -> (v[0] as String).lowercase() }
            on("bk.action.string.ToUpperCase") { v -> (v[0] as String).uppercase() }
            on("bk.action.string.Trim") { v -> (v[0] as String).trim() }
            on("bk.action.string.SplitWithString") { v -> (v[0] as String).split(v[1] as String).toMutableList() }
            on("bk.action.string.MatchesRegex") { v -> Regex(v[1] as String).matches(v[0] as String) }
            on("bk.action.string.Replace") { v ->
                val re = Regex(v[1] as String)
                if (truthy(v.getOrNull(3))) re.replaceFirst(v[0] as String, v[2] as String) else re.replace(v[0] as String, v[2] as String)
            }
            on("bk.action.string.ReplaceAll") { v -> (v[0] as String).replace(v[1] as String, v[2] as String) }
            on("bk.action.string.AsInteger") { v -> (v.getOrNull(0) as? String)?.trim()?.toIntOrNull() }
            on("bk.action.string.AsLong") { v -> (v.getOrNull(0) as? String)?.trim()?.toLongOrNull() }
            on("bk.action.string.Base64Encode") { v -> Base64.getEncoder().encodeToString((v[0] as String).toByteArray()) }
            on("bk.action.string.ParseUrl") { v -> parseUrl(v[0] as String) }
            // What the app sends until it has fetched a password key: the password in TLS, tagged.
            on("bk.action.string.EncryptPassword") { v -> (v[0] as String).let { if (it.isEmpty()) it else "#PWD_ENC:0:${System.currentTimeMillis() / 1000}:$it" } }

            // ---- JavaScript-flavoured helpers ----
            on("bk.action.js.string.Replace") { v ->
                val s = v[0] as String
                val pattern = v[1] as String
                if (pattern.isEmpty()) (v[2] as String) + s else s.replaceFirst(pattern, v[2] as String)
            }
            on("bk.action.js.string.ReplaceAll") { v ->
                val s = v[0] as String
                val pattern = v[1] as String
                val replacement = v[2] as String
                if (pattern.isEmpty()) buildString { s.forEach { append(replacement).append(it) }; append(replacement) } else s.replace(pattern, replacement)
            }
            on("bk.action.js.string.CharAt") { v -> (v[0] as String).getOrNull((v[1] as Number).toInt())?.toString() ?: "" }
            on("bk.action.js.string.CharCodeAt") { v -> (v[0] as String).getOrNull((v[1] as Number).toInt())?.code ?: Double.NaN }
            on("bk.action.js.string.IndexOf") { v -> (v[0] as String).indexOf(v[1] as String, ((v.getOrNull(2) as? Number)?.toInt() ?: 0).coerceAtLeast(0)) }
            on("bk.action.js.string.LastIndexOf") { v ->
                val s = v[0] as String
                val pos = ((v.getOrNull(2) as? Number)?.toInt() ?: s.length).coerceIn(0, s.length)
                s.lastIndexOf(v[1] as String, pos)
            }
            on("bk.action.js.string.Repeat") { v ->
                val s = v[0] as String
                val n = (v[1] as Number).toInt()
                if (n < 0) throw BloksException("RangeError: Invalid count value: $n")
                s.repeat(n)
            }
            on("bk.action.js.string.Slice") { v ->
                val s = v[0] as String
                fun clamp(x: Int) = (if (x < 0) x + s.length else x).coerceIn(0, s.length)
                val start = clamp((v[1] as Number).toInt())
                val end = clamp((v.getOrNull(2) as? Number)?.toInt() ?: s.length)
                if (start < end) s.substring(start, end) else ""
            }
            on("bk.action.js.string.Substring") { v ->
                val s = v[0] as String
                val a = (v[1] as Number).toInt().coerceIn(0, s.length)
                val b = ((v.getOrNull(2) as? Number)?.toInt() ?: s.length).coerceIn(0, s.length)
                s.substring(minOf(a, b), maxOf(a, b))
            }
            on("bk.action.js.string.Trim") { v -> (v[0] as String).trim { it.isWhitespace() || it == '\uFEFF' } }
            on("bk.action.js.number.IsInteger") { v -> (v.getOrNull(0) as? Number)?.toDouble()?.let { it.isFinite() && it == Math.rint(it) } ?: false }
            on("bk.action.js.array.IndexOf") { v ->
                val list = v[0] as List<*>
                var from = (v.getOrNull(2) as? Number)?.toInt() ?: 0
                if (from < 0) from = maxOf(from + list.size, 0)
                (from until list.size).firstOrNull { strictEqual(list[it], v[1]) } ?: -1
            }
            on("bk.action.js.array.LastIndexOf") { v ->
                val list = v[0] as List<*>
                if (list.isEmpty()) return@on -1
                var from = (v.getOrNull(2) as? Number)?.toInt() ?: (list.size - 1)
                if (from >= list.size) from = list.size - 1
                if (from < 0) from += list.size
                if (from < 0) -1 else (from downTo 0).firstOrNull { strictEqual(list[it], v[1]) } ?: -1
            }
            on("bk.action.js.array.Find") { v -> (v[0] as List<*>).withIndex().firstOrNull { (i, x) -> strict(call(v[1], listOf(x, i.toLong(), v[0]))) }?.value }
            on("bk.action.js.array.FindIndex") { v -> (v[0] as List<*>).withIndex().firstOrNull { (i, x) -> strict(call(v[1], listOf(x, i.toLong(), v[0]))) }?.index?.toLong() ?: -1L }
            on("bk.action.js.array.At") { v ->
                val l = v[0] as List<*>
                val i = (v[1] as Number).toInt()
                l.getOrNull(if (i < 0) i + l.size else i)
            }
            on("bk.action.js.array.Slice") { v ->
                val l = v[0] as List<*>
                fun clamp(x: Int) = (if (x < 0) x + l.size else x).coerceIn(0, l.size)
                val start = clamp((v[1] as Number).toInt())
                val end = clamp((v.getOrNull(2) as? Number)?.toInt() ?: l.size)
                if (start < end) ArrayList(l.subList(start, end)) else ArrayList<Any?>()
            }
            on("bk.action.js.array.Join") { v -> (v[0] as Iterable<*>).joinToString(v.getOrNull(1)?.let(::jsString) ?: ",") { if (it == null) "" else jsString(it) } }
            on("bk.action.js.syntax.StrictEqual") { v -> strictEqual(v.getOrNull(0), v.getOrNull(1)) }

            // ---- Numbers ----
            on("bk.action.f32.Eq", "bk.action.i32.Eq", "bk.action.i64.Eq") { v -> equal(v.getOrNull(0), v.getOrNull(1)) }
            on("bk.action.f32.Gt", "bk.action.i32.Gt", "bk.action.i64.Gt") { v -> compare(v.getOrNull(0), v.getOrNull(1)) > 0 }
            on("bk.action.f32.Lt", "bk.action.i32.Lt", "bk.action.i64.Lt") { v -> compare(v.getOrNull(0), v.getOrNull(1)) < 0 }
            on("bk.action.mins.Ge") { v -> compare(v.getOrNull(0), v.getOrNull(1)) >= 0 }
            on("bk.action.mins.Le") { v -> compare(v.getOrNull(0), v.getOrNull(1)) <= 0 }
            on("bk.action.f32.Add", "bk.action.i32.Add", "bk.action.i64.Add", "bk.action.num.Add") { v -> arith(v.getOrNull(0), v.getOrNull(1), Double::plus, Long::plus) }
            on("bk.action.f32.Sub", "bk.action.i32.Sub", "bk.action.i64.Sub", "bk.action.num.Sub") { v -> arith(v.getOrNull(0), v.getOrNull(1), Double::minus, Long::minus) }
            on("bk.action.f32.Mul", "bk.action.i32.Mul", "bk.action.i64.Mul", "bk.action.num.Mul") { v -> arith(v.getOrNull(0), v.getOrNull(1), Double::times, Long::times) }
            on("bk.action.f32.Div") { v -> norm(num(v[0]) / num(v[1])) }
            on("bk.action.i32.Div") { v -> norm(truncate(num(v[0]) / num(v[1]))) }
            on("bk.action.i32.Mod") { v -> norm(truncate(num(v[0]) % num(v[1]))) }
            on("bk.action.i64.Div") { v ->
                val d = toI64(v[1])
                if (d == 0L) throw BloksException("Division by Zero")
                toI64(v[0]) / d
            }
            on("bk.action.i32.And") { v -> toI32(v[0]) and toI32(v[1]) }
            on("bk.action.i32.Or") { v -> toI32(v[0]) or toI32(v[1]) }
            on("bk.action.i32.Rand") { v -> norm(truncate(num(v[0]) + (num(v[1]) - num(v[0])) * Math.random())) }
            on("bk.action.f32.Log") { v -> norm(Math.log((v[0] as Number).toDouble())) }
            on("bk.action.f32.Pow") { v -> norm(Math.pow((v[0] as Number).toDouble(), (v[1] as Number).toDouble())) }
            on("bk.action.f32.Sqrt") { v -> norm(Math.sqrt((v[0] as Number).toDouble())) }
            // Degrees, as the app takes them.
            on("bk.action.f32.Cos") { v -> norm(Math.cos(Math.toRadians((v[0] as Number).toDouble()))) }
            on("bk.action.f32.Sin") { v -> norm(Math.sin(Math.toRadians((v[0] as Number).toDouble()))) }
            on("bk.action.num.Pow") { v -> pow(v[0], v[1]) }
            on("bk.action.math.Max") { v -> norm(v.maxOf { num(it) }) }
            on("bk.action.math.Min") { v -> norm(v.minOf { num(it) }) }
            on("bk.action.math.Abs") { v -> norm(abs(num(v[0]))) }
            on("bk.action.math.Floor") { v -> norm(floor(num(v[0]))) }
            on("bk.action.math.Ceil") { v -> norm(ceil(num(v[0]))) }
            on("bk.action.bool.Not") { v -> !truthy(v.getOrNull(0)) }
            on("bk.action.bool.Or") { v -> truthy(toI32(v[0]) or toI32(v[1])) }
            on("bk.action.bool.And") { v -> truthy(toI32(v[0]) and toI32(v[1])) }

            // ---- Time and locale ----
            on("bk.action.io.CurrentTimeMillis", "bk.action.io.CurrentTimeMillisV2") { System.currentTimeMillis() }
            // No touch recorded, as the app answers when none is.
            on("bk.action.bloks.TimestampOfLastTouchUpV2") { -1L }
            on("bk.action.GetClientTimezone") { TimeZone.getDefault().id }
            on("bk.action.StringToTimestamp") { v ->
                runCatching { SimpleDateFormat(v[1] as String, Locale.US).parse(v[0] as String)!!.time / 1000 }.getOrNull()
            }
            on("bk.action.GetDatetimeText") { v -> datetime(v) }
            on("bk.action.currency.GetFormattedCurrency") { v ->
                val currency = Currency.getInstance(v[0] as String)
                val digits = (v[1] as String).filter(Char::isDigit).ifEmpty { "0" }
                val amount = digits.toDouble() / Math.pow(10.0, currency.defaultFractionDigits.coerceAtLeast(0).toDouble())
                NumberFormat.getCurrencyInstance().apply { this.currency = currency }.format(if (amount <= 0) 0.0 else amount)
            }
            // A language pack lookup needs Meta's FlatBuffer packs; the scripts fall back to their own text.
            on("bk.action.i18n.LanguagePackResolveFbt", "bk.action.i18n.LanguagePackResolveFbtV2") { null }
        }

        /** null, false, "", 0 and NaN are false; everything else, empty lists and maps included, is true. */
        fun truthy(x: Any?): Boolean = when (x) {
            null -> false
            is Boolean -> x
            is String -> x.isNotEmpty()
            is Long -> x != 0L
            is Number -> x.toDouble().let { it != 0.0 && !it.isNaN() }
            else -> true
        }

        /** Predicate results of Filter and friends: a number by its int value, or a Boolean. */
        private fun strict(x: Any?): Boolean = when (x) {
            is Boolean -> x
            is Number -> x.toInt() != 0
            else -> throw BloksException("predicate returned ${kind(x)}")
        }

        /** Bloks equality: numbers and booleans by value, strings by content, containers and lambdas by identity. */
        fun equal(a: Any?, b: Any?): Boolean = when {
            a == null || b == null -> a == null && b == null
            a is Boolean && b is Boolean -> a == b
            a is Long && b is Long -> a == b
            (a is Number || a is Boolean) && (b is Number || b is Boolean) -> num(a) == num(b)
            a is String || b is String -> a is String && b is String && a == b
            a is List<*> || a is Map<*, *> || a is Lambda -> a === b
            else -> a == b
        }

        /** JavaScript's `===` on Java values: a Boolean never equals a Number, NaN never equals anything. */
        private fun strictEqual(a: Any?, b: Any?): Boolean = when {
            a == null || b == null -> a == null && b == null
            a is Boolean || b is Boolean -> a is Boolean && b is Boolean && a == b
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is String && b is String -> a == b
            else -> a === b
        }

        fun compare(a: Any?, b: Any?): Int = when {
            a is Long && b is Long -> a.compareTo(b)
            a is String && b is String -> a.compareTo(b)
            (a is Number || a is Boolean) && (b is Number || b is Boolean) -> num(a).compareTo(num(b))
            else -> throw BloksException("cannot order ${kind(a)} and ${kind(b)}")
        }

        fun typeOf(x: Any?): Int = when (x) {
            null -> 0
            is Boolean -> 1
            is String -> 2
            is Long -> 3
            is Number -> 4
            is List<*> -> 6
            is Map<*, *> -> 7
            is Lambda -> 8
            else -> 5
        }

        fun num(x: Any?): Double = when (x) {
            is Number -> x.toDouble()
            is Boolean -> if (x) 1.0 else 0.0
            else -> throw BloksException("not a number: ${kind(x)}")
        }

        fun int(x: Any?): Int = (x as? Number)?.toInt() ?: throw BloksException("not an index: ${kind(x)}")

        /** An index as the app checks it: a whole, non-negative number. */
        private fun index(x: Any?): Int {
            val d = (x as? Number)?.toDouble() ?: throw BloksException("not an index: ${kind(x)}")
            if (d < 0 || d != Math.rint(d)) throw BloksException("$d is an invalid index")
            return d.toInt()
        }

        /** Java's `(int)` cast, as the app's bit operations use: truncates and saturates, NaN is 0. */
        private fun toI32(x: Any?): Int = num(x).toInt()

        private fun toI64(x: Any?): Long = when (x) {
            is Long -> x
            is Boolean -> if (x) 1L else 0L
            is Number -> x.toDouble().toLong()
            else -> throw BloksException("Cannot cast ${kind(x)} to i64")
        }

        /** A double that is an exact int32 becomes an Int. */
        fun norm(d: Double): Any = if (d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE && !(d == 0.0 && 1 / d < 0)) d.toInt() else d

        /** Long arithmetic when both are integral and a Long is involved, as `num.Add` does; doubles otherwise. */
        private fun arith(a: Any?, b: Any?, d: (Double, Double) -> Double, l: (Long, Long) -> Long): Any {
            if ((a is Long || b is Long) && (a is Long || a is Int) && (b is Long || b is Int)) {
                val r = l((a as Number).toLong(), (b as Number).toLong())
                return if (r in Int.MIN_VALUE..Int.MAX_VALUE) r.toInt() else r
            }
            return norm(d(num(a), num(b)))
        }

        /** `num.Pow`: doubles in the int32 range, exact longs past it, doubles again on overflow. */
        private fun pow(a: Any?, b: Any?): Any {
            val d = Math.pow(num(a), num(b))
            fun whole(x: Any?) = x !is Double || (x.isFinite() && x == Math.rint(x) && abs(x) < Long.MAX_VALUE.toDouble())
            fun small(x: Any?) = num(x).let { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
            if (!whole(a) || !whole(b) || (small(a) && small(b) && abs(d) < 2147483648.0)) return norm(d)
            val base = toI64(a)
            val exp = toI64(b)
            return when {
                exp < 0 -> Math.pow(base.toDouble(), exp.toDouble())
                base == 0L -> if (exp == 0L) 1L else 0L
                base == 1L -> 1L
                base == -1L -> if (exp % 2 == 0L) 1L else -1L
                else -> {
                    var r = 1L
                    for (i in 0 until exp) {
                        r = try {
                            Math.multiplyExact(r, base)
                        } catch (_: ArithmeticException) {
                            return Math.pow(base.toDouble(), exp.toDouble())
                        }
                    }
                    r
                }
            }
        }

        fun valueOfNumber(x: Any?): String = when (x) {
            is String -> x
            is Boolean -> x.toString()
            is Long -> x.toString()
            is Int -> x.toString()
            is Number -> {
                val d = x.toDouble()
                if (d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt().toString()
                else d.toString().replace("E", "e").replace(Regex("e(\\d)"), "e+$1")
            }
            else -> throw BloksException("ValueOfNumber of ${kind(x)}")
        }

        /** JavaScript's string conversion for Join. */
        private fun jsString(x: Any): String = when (x) {
            is String -> x
            is Boolean -> x.toString()
            is Number -> {
                val d = x.toDouble()
                when {
                    d.isNaN() -> "NaN"
                    d == Double.POSITIVE_INFINITY -> "Infinity"
                    d == Double.NEGATIVE_INFINITY -> "-Infinity"
                    d == Math.rint(d) && abs(d) < Long.MAX_VALUE.toDouble() -> d.toLong().toString()
                    else -> d.toString()
                }
            }
            is List<*> -> x.joinToString(",") { if (it == null) "" else jsString(it) }
            else -> "[object Object]"
        }

        private fun isChild(x: Any?): Boolean = x is Node || x is BloksSession.Instance

        @Suppress("UNCHECKED_CAST")
        private fun put(container: Any?, key: Any?, value: Any?, quiet: Boolean): Any? {
            try {
                when (container) {
                    is MutableList<*> -> {
                        val list = container as MutableList<Any?>
                        val i = index(key)
                        when {
                            i < list.size -> list[i] = value
                            i == list.size -> list.add(value)
                            else -> throw BloksException("$i is an invalid index into an array of size ${list.size}")
                        }
                    }
                    is MutableMap<*, *> -> (container as MutableMap<Any?, Any?>)[key] = value
                    else -> throw BloksException("put into ${kind(container)}")
                }
            } catch (error: UnsupportedOperationException) {
                if (!quiet) throw BloksException("Tried to insert a value into an immutable container")
            }
            return null
        }

        private fun pandoWalk(v: List<Any?>, require: Boolean): Any? {
            var cur: Any? = v.getOrNull(0)
            val path = StringBuilder()
            for (field in v.drop(1)) {
                path.append('.').append(field)
                cur = (cur as? Map<*, *>)?.get(field)
                if (cur == null) {
                    if (require) throw BloksException("BKPandoRequire: Got unexpected null at field $path")
                    return null
                }
            }
            return cur
        }

        private fun parseUrl(url: String): Map<String, Any?> {
            val uri = runCatching { URI(url) }.getOrNull()
            val params = LinkedHashMap<String, String>()
            uri?.rawQuery?.split('&')?.forEach { pair ->
                val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                if (k.isNotEmpty() && k !in params) params[k] = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            return linkedMapOf(
                "scheme" to uri?.scheme,
                "host" to uri?.host,
                "port" to uri?.port?.takeIf { it >= 0 },
                "path" to uri?.path,
                "url_params" to params,
                "fragment" to uri?.fragment,
            )
        }

        private fun datetime(v: List<Any?>): String {
            val seconds = (v[0] as Number).toLong()
            val kind = v[1] as String
            fun style(s: Any?): Int? = when (s) {
                "short" -> DateFormat.SHORT
                "medium" -> DateFormat.MEDIUM
                "long" -> DateFormat.LONG
                "full" -> DateFormat.FULL
                else -> null
            }
            val date = style(v[2])
            val time = style(v.getOrNull(3)) ?: date ?: DateFormat.MEDIUM
            val format = when (kind) {
                "date" -> if (date == null) SimpleDateFormat(v[2] as String, Locale.US) else DateFormat.getDateInstance(date)
                "time" -> DateFormat.getTimeInstance(time)
                "datetime" -> DateFormat.getDateTimeInstance(date ?: DateFormat.MEDIUM, time)
                else -> throw BloksException("Unknown dateformat type: $kind")
            }
            (v.getOrNull(5) as? String)?.let { format.timeZone = TimeZone.getTimeZone(it) }
            return format.format(Date(seconds * 1000))
        }

        /** `mins.CallRuntime`: 2 to number, 6 a map (keys first, then values), 8 to int64, 10 substring, 17 a map (pairs). */
        private fun runtime(op: Int, v: List<Any?>): Any? = when (op) {
            2 -> when (val x = v.getOrNull(0)) {
                null -> null
                is Boolean -> if (x) 1 else 0
                is Number -> norm(x.toDouble())
                is String -> if ('x' in x || x.any { it < ' ' }) null else x.trim().toDoubleOrNull()?.let(::norm)
                else -> null
            }
            6 -> {
                if (v.size % 2 != 0) throw BloksException("odd map literal")
                val n = v.size / 2
                LinkedHashMap<Any?, Any?>().apply { for (i in 0 until n) put(v[i], v[n + i]) }
            }
            8 -> when (val x = v.getOrNull(0)) {
                null -> null
                is Boolean -> if (x) 1L else 0L
                is Number -> x.toDouble().toLong()
                is String -> x.trim().toLongOrNull()
                else -> null
            }
            10 -> {
                val s = v[0] as String
                val off = int(v[1]).let { if (it < 0) s.length + it else it }
                val len = (v.getOrNull(2) as? Number)?.toInt() ?: (s.length - off)
                s.substring(off, off + len)
            }
            17 -> LinkedHashMap<Any?, Any?>().apply { for (i in v.indices step 2) put(v[i], v.getOrNull(i + 1)) }
            else -> throw BloksException("Unknown runtime function $op")
        }

        fun kind(x: Any?): String = when (x) {
            null -> "null"
            is Node -> "node ${x.type}"
            else -> x.javaClass.simpleName
        }
    }
}
