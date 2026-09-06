package io.github.tonyxmelon.aisudoku.recognize

import io.github.tonyxmelon.aisudoku.vision.CellExtractor
import io.github.tonyxmelon.aisudoku.vision.CellGeometry
import io.github.tonyxmelon.aisudoku.vision.GrayImage
import io.github.tonyxmelon.aisudoku.vision.OpenCvNatives
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Reads an already-rectified grid and says what the triage made of every square.
 *
 * The app keeps the rectified square rather than the photograph, so a puzzle that read
 * badly on the phone arrives here as a 1152 pixel grid with no scene around it - which
 * the corpus harness cannot take, because it expects a photograph with a grid to find.
 * This takes the square as it is.
 *
 *   ./gradlew :core:recognize:test --tests '*RectifiedProbeTest*' -Dprobe=<path> --rerun-tasks
 */
class RectifiedProbeTest {

    @Test
    fun `say what the triage made of every square`() {
        val path = System.getProperty("probe")?.takeIf { it.isNotBlank() } ?: return
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }

        val image = ImageIO.read(File(path))
        val pixels = ByteArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val grey = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 +
                    (rgb and 0xFF) * 114) / 1000
                pixels[y * image.width + x] = grey.toByte()
            }
        }
        val rectified = GrayImage(image.width, image.height, pixels)
        val cells = CellExtractor.extract(rectified, CellGeometry.evenNinths(image.width))

        val reader = GridReader()
        val readings = when (val result = reader.read(cells)) {
            is ReadResult.Accepted -> result.readings
            is ReadResult.NeedsConfirmation -> result.readings
            is ReadResult.Unreadable -> {
                println("PROBE unreadable")
                return
            }
        }
        val inks = CellAnalyzer.inspect(cells)
        val core = reader.findPrintedCore(inks.mapNotNull { it?.blob })
        println("PROBE core $core")
        println("PROBE\tcell\trc\tink\tdigit\trelHeight\tvOffset\tcontrast\tstroke\tcompany\toutshone")
        for (reading in readings) {
            val blob = inks[reading.index]?.blob
            val relative = if (blob == null || core == null) 0.0 else blob.heightRatio / core.height
            println(
                "PROBE\t${reading.index}\tr${reading.index / 9 + 1}c${reading.index % 9 + 1}\t" +
                    "${reading.ink}\t${reading.digit ?: 0}\t" +
                    "%.3f\t%.3f\t%.1f\t%.2f\t%d\t%.1f".format(
                        relative,
                        blob?.verticalOffset ?: 0.0,
                        blob?.contrast ?: 0.0,
                        blob?.strokeWidth ?: 0.0,
                        inks[reading.index]?.company ?: 0,
                        inks[reading.index]?.outshoneBy ?: 0.0,
                    )
            )
        }
    }
}
