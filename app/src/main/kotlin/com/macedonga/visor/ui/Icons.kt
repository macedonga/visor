package com.macedonga.visor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's icons: thin strokes on a 24 grid, drawn here rather than borrowed, tinted by whoever shows them. */
object VisorIcons {
    val Gallery = icon(
        "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5z",
        "M3.8 16.2l5.2-4.7 3.8 3.4 2.6-2.2 4.8 4.1",
        "M15.5 8.2m-1.4 0a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0",
    )

    /** A point with two rings of waves either side: the camera broadcasting. */
    val Live = icon(
        "M12 12m-1.6 0a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0-3.2 0",
        "M8.6 8.6a4.8 4.8 0 0 0 0 6.8",
        "M15.4 8.6a4.8 4.8 0 0 1 0 6.8",
        "M5.8 5.8a8.8 8.8 0 0 0 0 12.4",
        "M18.2 5.8a8.8 8.8 0 0 1 0 12.4",
    )

    /** A voice: the assistant lives in the glasses' speakers and microphones. */
    val Voice = icon("M5.5 10.5v3", "M8.75 8v8", "M12 5v14", "M15.25 8v8", "M18.5 10.5v3")

    val Settings = icon(
        "M4 8h9", "M17 8h3", "M15 8m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "M4 16h3", "M11 16h9", "M9 16m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
    )

    val Chevron = icon("M10 6.5l5.5 5.5-5.5 5.5")

    val Glasses = icon(
        "M7 13m-3.3 0a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0-6.6 0",
        "M17 13m-3.3 0a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0-6.6 0",
        "M10.3 12.4q1.7-1.1 3.4 0",
        "M3.7 12.2L2.4 9.6",
        "M20.3 12.2l1.3-2.6",
    )

    val Case = icon("M6 8h12a4 4 0 0 1 0 8H6a4 4 0 0 1 0-8z", "M2.5 12h19")

    /** The Neural Band: the strap seen edge on, the pod on top. */
    val Band = icon("M5 12.5a7 4.5 0 1 0 14 0a7 4.5 0 1 0-14 0", "M9.8 6.3h4.4a1 1 0 0 1 1 1v1.2H8.8V7.3a1 1 0 0 1 1-1z")

    /** A turn back round the clock: what just happened. */
    val Replay = icon("M5.2 13.5A7 7 0 1 0 7 7.1", "M6.6 3.6l.4 3.5 3.5-.4")

    val Camera = icon(
        "M4.5 8h3l1.5-2.2h6L16.5 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z",
        "M12 13.3m-3.3 0a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0-6.6 0",
    )

    val Speaker = icon("M4.5 9.5h3l4-3.5v12l-4-3.5h-3z", "M15 9.5a3.5 3.5 0 0 1 0 5", "M17.6 7a7 7 0 0 1 0 10")

    val SpeakerOff = icon("M4.5 9.5h3l4-3.5v12l-4-3.5h-3z", "M15.5 10l4 4", "M19.5 10l-4 4")

    val Heat = icon("M10 14.4V5.5a2 2 0 0 1 4 0v8.9a3.6 3.6 0 1 1-4 0z", "M12 16.5v-6")

    val Battery = icon("M5 8h12a1.5 1.5 0 0 1 1.5 1.5v5A1.5 1.5 0 0 1 17 16H5a1.5 1.5 0 0 1-1.5-1.5v-5A1.5 1.5 0 0 1 5 8z", "M21 11v2")

    val Grid = icon("M9 4.5v15", "M15 4.5v15", "M4.5 9h15", "M4.5 15h15")

    val Bolt = icon("M13 3.5L7 13h4.5l-1 7.5L17 11h-4.5z", filled = true)

    private const val HEART = "M12 19.6C7.3 16.3 4.5 13.4 4.5 10.1 4.5 7.8 6.3 6 8.5 6c1.5 0 2.8.8 3.5 2 .7-1.2 2-2 3.5-2 2.2 0 4 1.8 4 4.1 0 3.3-2.8 6.2-7.5 9.5z"
    val Heart = icon(HEART)
    val HeartFilled = icon(HEART, filled = true)

    val Star = icon("M12 3.8L14.12 9.49 20.18 9.74 15.42 13.51 17.06 19.36 12 16 6.94 19.36 8.58 13.51 3.82 9.74 9.88 9.49z", filled = true)

    val Share = icon(
        "M12 3.8v11", "M8.2 7.4L12 3.8l3.8 3.6",
        "M7 10.5h-.5A1.5 1.5 0 0 0 5 12v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12a1.5 1.5 0 0 0-1.5-1.5H17",
    )

    val Download = icon(
        "M12 3.8v11", "M8.2 11.2L12 14.8l3.8-3.6",
        "M5 15.5v3A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5v-3",
    )

    val Trash = icon(
        "M4.5 6.5h15", "M9.5 6.5V5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1.5",
        "M6.5 6.5l.8 12a1.5 1.5 0 0 0 1.5 1.4h6.4a1.5 1.5 0 0 0 1.5-1.4l.8-12", "M10 10.5v5.5", "M14 10.5v5.5",
    )

    val Close = icon("M6.5 6.5l11 11", "M17.5 6.5l-11 11")

    val ChevronDown = icon("M6.5 9.5l5.5 5.5 5.5-5.5")

    val ChevronLeft = icon("M14 6.5l-5.5 5.5 5.5 5.5")

    val Mic = icon(
        "M12 3.5a2.5 2.5 0 0 1 2.5 2.5v5.5a2.5 2.5 0 0 1-5 0V6A2.5 2.5 0 0 1 12 3.5z",
        "M6.5 11a5.5 5.5 0 0 0 11 0",
        "M12 16.5v3.5",
    )

    val Send = icon("M12 19V5.5", "M6.5 11l5.5-5.5 5.5 5.5")

    val Search = icon("M10.5 10.5m-6 0a6 6 0 1 0 12 0a6 6 0 1 0-12 0", "M15 15l4.5 4.5")

    /** A notebook: what the assistant keeps about you. */
    val Memory = icon(
        "M7 4h10.5A1.5 1.5 0 0 1 19 5.5V20H8.5A2.5 2.5 0 0 1 6 17.5V5a1 1 0 0 1 1-1z",
        "M6 17.5A2.5 2.5 0 0 1 8.5 15H19",
        "M10 8h5",
    )

    val Copy = icon(
        "M10 9h8.5a1.5 1.5 0 0 1 1.5 1.5V19a1.5 1.5 0 0 1-1.5 1.5H10A1.5 1.5 0 0 1 8.5 19v-8.5A1.5 1.5 0 0 1 10 9z",
        "M15.5 9V5.5A1.5 1.5 0 0 0 14 4H5.5A1.5 1.5 0 0 0 4 5.5V14a1.5 1.5 0 0 0 1.5 1.5h3",
    )

    /** An arrow turning back into the thread: carry on from here. */
    val Reply = icon("M9.5 6.5L5 11l4.5 4.5", "M5 11h9.5a4.5 4.5 0 0 1 4.5 4.5v2")

    val Plus = icon("M12 5.5v13", "M5.5 12h13")

    val Check = icon("M7 12.5l3.2 3.2L17 9")

    val Play = icon("M8.5 6.2v11.6l9.2-5.8z", filled = true)

    val Pause = icon("M9 6.5v11", "M15 6.5v11")

    /** Crop and turn. */
    val Edit = icon("M7 3.5V17h13.5", "M3.5 7H17v13.5")

    val Pin = icon("M12 20.5s-6-5.4-6-10.2a6 6 0 0 1 12 0c0 4.8-6 10.2-6 10.2z", "M12 10.3m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0")

    /** A live photo: a point, a ring, and a broken ring around them. */
    val LivePhoto = icon(
        "M12 12m-1.8 0a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0",
        "M12 12m-4.8 0a4.8 4.8 0 1 0 9.6 0a4.8 4.8 0 1 0-9.6 0",
        *dashedRing(8.3f, 16),
    )

    val Bell = icon("M6.5 10.5a5.5 5.5 0 0 1 11 0V15l1.5 2h-14l1.5-2z", "M10 19.5a2 2 0 0 0 4 0")

    val Moon = icon("M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z")

    val Globe = icon(
        "M12 12m-8 0a8 8 0 1 0 16 0a8 8 0 1 0-16 0",
        "M4 12h16",
        "M12 4c2.4 2.2 3.5 5 3.5 8s-1.1 5.8-3.5 8c-2.4-2.2-3.5-5-3.5-8s1.1-5.8 3.5-8z",
    )

    /** A tap and its ripples. */
    val Touch = icon("M12 12m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0", "M7.8 16.2a6 6 0 1 1 8.4 0", "M5 19a10 10 0 1 1 14 0")

    val Shield = icon("M12 3.5l7 2.8v5.2c0 4.3-2.9 7.7-7 9-4.1-1.3-7-4.7-7-9V6.3z")

    val Phone = icon("M8 3.5h8a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5z", "M10.5 17.5h3")

    val Sliders = icon(
        "M5 7h8", "M17 7h2", "M15 7m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "M5 17h2", "M11 17h8", "M9 17m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
    )

    val Info = icon("M12 12m-8 0a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M12 11v5", "M12 8h.01")

    val Archive = icon("M4.5 6h15v3.5h-15z", "M5.5 9.5v9h13v-9", "M10 13h4")

    val Refresh = icon("M18.5 12a6.5 6.5 0 1 1-1.9-4.6", "M18.8 4.5v3.5h-3.5")

    val Flag = icon("M6 20.5V4", "M6 4.5h11l-2 4 2 4H6")

    val Pulse = icon("M3.5 12h4l2-5 4 10 2-5h5")

    val Lines = icon("M8 7h11", "M8 12h11", "M8 17h11", "M5 7h.01", "M5 12h.01", "M5 17h.01")

    /** A plug: what the assistant is connected to. */
    val Plug = icon("M9 3.5v4", "M15 3.5v4", "M6.5 7.5h11v3a5.5 5.5 0 0 1-11 0z", "M12 16v4.5")

    /** A four-point star: something the glasses can do that Meta keeps off. */
    val Spark = icon("M12 3.5C12.7 9.2 14.8 11.3 20.5 12C14.8 12.7 12.7 14.8 12 20.5C11.3 14.8 9.2 12.7 3.5 12C9.2 11.3 11.3 9.2 12 3.5z")

    /** An ear: hearing the person in front of you. */
    val Ear = icon("M7.5 9.5a4.5 4.5 0 1 1 9 0c0 2.6-1.8 3.6-2.8 4.6-.8.8-1 1.6-1.2 2.6a2.6 2.6 0 0 1-5 .3", "M10 9.8a2 2 0 1 1 3.6 1.2")

    /** The ear with sound coming to it: hearing correction. */
    val HearingAid = icon(
        "M5.5 9.5a4.5 4.5 0 1 1 9 0c0 2.6-1.8 3.6-2.8 4.6-.8.8-1 1.6-1.2 2.6a2.6 2.6 0 0 1-5 .3", "M8 9.8a2 2 0 1 1 3.6 1.2",
        "M17.5 8a5 5 0 0 1 0 5", "M19.8 6a8 8 0 0 1 0 9",
    )

    val Minus = icon("M5.5 12h13")

    val Timer = icon("M12 13.5m-6.5 0a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0", "M12 13.5V10", "M10 3.5h4", "M12 3.5V7")

    val Captions = icon(
        "M6 6.5h12a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 16V8A1.5 1.5 0 0 1 6 6.5z",
        "M7.5 11h4", "M13.5 11h3", "M7.5 14h7",
    )

    val Mirror = icon("M4 5.5h16v11H4z", "M9.5 19.5h5", "M12 16.5v3")

    val People = icon(
        "M9 9m-2.5 0a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0", "M4 18.5a5 5 0 0 1 10 0",
        "M16 9.5m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M15.5 14a4.2 4.2 0 0 1 4.5 4.5",
    )

    val Chat = icon("M5.5 6h13a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5H10l-4 3v-3h-.5A1.5 1.5 0 0 1 4 14.5v-7A1.5 1.5 0 0 1 5.5 6z")

    /** Short arcs around the centre of the grid, [count] of them, each half a step long. */
    private fun dashedRing(radius: Float, count: Int): Array<String> = Array(count) { k ->
        val step = 2 * Math.PI / count
        val a0 = k * step
        val a1 = a0 + step / 2
        fun x(a: Double) = 12 + radius * kotlin.math.cos(a)
        fun y(a: Double) = 12 + radius * kotlin.math.sin(a)
        "M%.2f %.2fA%.2f %.2f 0 0 1 %.2f %.2f".format(java.util.Locale.ROOT, x(a0), y(a0), radius, radius, x(a1), y(a1))
    }

    private fun icon(vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.White) else null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
