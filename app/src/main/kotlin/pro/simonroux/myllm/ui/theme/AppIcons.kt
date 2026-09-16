package pro.simonroux.myllm.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of icons this app needs that are not in material-icons-core.
 *
 * Depending on material-icons-extended for them cost 40 MB of dex in an
 * unminified build, which on a sideloaded app is 40 MB of mobile data on every
 * install. Four shapes drawn by hand are a better trade than a library of
 * several thousand.
 *
 * Fill and stroke colours are placeholders: Icon() tints the whole vector, so
 * whatever is set here is replaced at draw time.
 */
object AppIcons {

    /** Speech bubble. Conversations. */
    val Chat: ImageVector = icon("Chat") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            lineTo(20f, 16f)
            lineTo(10f, 16f)
            lineTo(6f, 20f)
            lineTo(6f, 16f)
            lineTo(4f, 16f)
            close()
        }
    }

    /** A chip with its pins. Models, which is where the weights live. */
    val Chip: ImageVector = icon("Chip") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 7f)
            lineTo(17f, 7f)
            lineTo(17f, 17f)
            lineTo(7f, 17f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // Pins, one run per edge.
            moveTo(10f, 7f); lineTo(10f, 4f)
            moveTo(14f, 7f); lineTo(14f, 4f)
            moveTo(10f, 17f); lineTo(10f, 20f)
            moveTo(14f, 17f); lineTo(14f, 20f)
            moveTo(7f, 10f); lineTo(4f, 10f)
            moveTo(7f, 14f); lineTo(4f, 14f)
            moveTo(17f, 10f); lineTo(20f, 10f)
            moveTo(17f, 14f); lineTo(20f, 14f)
        }
    }

    /** Four blocks. Skills, which are pieces added to the whole. */
    val Blocks: ImageVector = icon("Blocks") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 4f); lineTo(11f, 4f); lineTo(11f, 11f); lineTo(4f, 11f); close()
            moveTo(13f, 4f); lineTo(20f, 4f); lineTo(20f, 11f); lineTo(13f, 11f); close()
            moveTo(4f, 13f); lineTo(11f, 13f); lineTo(11f, 20f); lineTo(4f, 20f); close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // The empty slot: what the app does not have yet and can be given.
            moveTo(13f, 13f); lineTo(20f, 13f); lineTo(20f, 20f); lineTo(13f, 20f); close()
        }
    }

    /** A filled square. Stopping a generation. */
    val Stop: ImageVector = icon("Stop") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 6f)
            lineTo(18f, 6f)
            lineTo(18f, 18f)
            lineTo(6f, 18f)
            close()
        }
    }

    private fun icon(
        name: String,
        content: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(content).build()
}
