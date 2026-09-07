package org.freevia.sudokubuddy.vision

import kotlin.test.Test
import kotlin.test.assertTrue

class CorpusFixturesTest {

    @Test
    fun `the corpus loads as grayscale images`() {
        CorpusFixtures.requireCorpus()
        for (file in CorpusFixtures.photos) {
            val image = CorpusFixtures.load(file)
            assertTrue(image.width > 500 && image.height > 500, "${file.name} is $image")
        }
    }
}
