package org.freevia.sudokubuddy.recognize

import org.freevia.sudokubuddy.vision.CellExtractor
import org.freevia.sudokubuddy.vision.CellGeometry
import org.freevia.sudokubuddy.vision.GrayImage
import org.freevia.sudokubuddy.vision.GridLineFitter
import org.freevia.sudokubuddy.vision.OpenCvNatives
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Exports the classifier's bitmaps for grids the app kept, rather than for photographs.
 *
 * The corpus is photographs, and every one of them costs a walk through the locator before
 * a cell can be cut. These are the other thing the app produces: the rectified square it
 * stores with a puzzle, which is what comes off a phone when a page has read badly. They
 * cannot be asked whether a grid can be found - the grid is already found, and the corpus
 * harness rightly calls such an image NoGrid - but the digits in them are as real as any,
 * and they are digits the corpus does not have: seven typefaces, two of them script, seen
 * through a lens and combed with the moire a camera makes of a monitor.
 *
 * They write into the same place [ExportNormalisedTest] does, so training reads them
 * without knowing the difference and scores them page by page like everything else.
 *
 * Disabled by default because it writes files:
 *   ./gradlew :core:recognize:test --tests '*ExportRectifiedTest*' -Ddump=true --rerun-tasks
 */
class ExportRectifiedTest {

    @Test
    fun `export the bitmap handed to the classifier for every rectified page`() {
        if (System.getProperty("dump") != "true") return
        val home = File("../../corpus-rectified")
        if (!home.isDirectory) {
            println("no corpus-rectified directory; nothing to export")
            return
        }
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }

        val root = File("build/normalised").apply { mkdirs() }
        var written = 0
        var pages = 0

        for (file in home.listFiles()!!.filter { it.extension == "jpg" }.sortedBy { it.name }) {
            val image = ImageIO.read(file) ?: continue
            val pixels = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    pixels[y * image.width + x] = (((rgb shr 16 and 0xFF) * 299 +
                        (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000).toByte()
                }
            }
            val rectified = GrayImage(image.width, image.height, pixels)
            val geometry = GridLineFitter.fit(rectified)
                ?: CellGeometry.evenNinths(image.width)
            val cells = CellExtractor.extract(rectified, geometry)

            val directory = File(root, file.nameWithoutExtension).apply { mkdirs() }
            CellAnalyzer.inspect(cells).forEachIndexed { index, ink ->
                if (ink == null) return@forEachIndexed
                DataOutputStream(
                    File(directory, "cell_%02d.f32".format(index)).outputStream().buffered()
                ).use { out ->
                    for (v in ink.normalised) {
                        out.writeInt(
                            java.lang.Integer.reverseBytes(java.lang.Float.floatToIntBits(v))
                        )
                    }
                }
                written++
            }
            pages++
        }
        println("exported $written cells from $pages rectified pages to ${root.absolutePath}")
    }
}
