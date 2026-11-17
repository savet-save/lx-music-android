package cn.toside.music.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class ImageViewportTest {
    private fun viewport(iw: Int, ih: Int, w: Int, h: Int) = ImageViewport().apply { image(iw, ih); resize(w, h) }
    @Test fun wideAndTallImagesFitCompletelyWithoutChangingAspectRatio() {
        val wide = viewport(1600, 900, 400, 700)
        assertEquals(400f, wide.scaledWidth, 0.01f); assertEquals(225f, wide.scaledHeight, 0.01f)
        assertEquals(0f, wide.x, 0.01f); assertEquals(237.5f, wide.y, 0.01f)
        val tall = viewport(300, 1800, 400, 700)
        assertEquals(700f, tall.scaledHeight, 0.01f); assertEquals(350f / 3, tall.scaledWidth, 0.01f)
        assertEquals((400 - tall.scaledWidth) / 2, tall.x, 0.01f)
    }
    @Test fun smallImagesAlsoUseTheLargestFitAndCannotDriftAtMinimumZoom() {
        val image = viewport(100, 100, 400, 700)
        assertEquals(4f, image.scale, 0.01f)
        image.panBy(300f, -500f)
        assertEquals(0f, image.x, 0.01f); assertEquals(150f, image.y, 0.01f)
    }
    @Test fun scalingKeepsThePointUnderTheFingersInPlace() {
        val image = viewport(1000, 1000, 400, 400)
        val pixelX = (150 - image.x) / image.scale
        val pixelY = (170 - image.y) / image.scale
        image.scaleBy(2f, 150f, 170f)
        assertEquals(150f, image.x + pixelX * image.scale, 0.01f)
        assertEquals(170f, image.y + pixelY * image.scale, 0.01f)
    }
    @Test fun draggingReachesAllFourEdgesWithoutExposingEmptySpace() {
        val image = viewport(800, 800, 400, 400)
        image.scaleBy(3f, 200f, 200f); image.panBy(10000f, 10000f)
        assertEquals(0f, image.x, 0.01f); assertEquals(0f, image.y, 0.01f)
        image.panBy(-20000f, -20000f)
        assertEquals(-800f, image.x, 0.01f); assertEquals(-800f, image.y, 0.01f)
    }
    @Test fun anAxisThatStillFitsStaysCenteredWhileTheOtherCanMove() {
        val image = viewport(2000, 300, 400, 700)
        image.scaleBy(3f, 200f, 350f); image.panBy(-100f, 90f)
        assertEquals(-500f, image.x, 0.01f); assertEquals((700 - image.scaledHeight) / 2, image.y, 0.01f)
    }
    @Test fun zoomHasBoundsAndReturningToFitRecentersTheWholeImage() {
        val image = viewport(900, 1600, 400, 700)
        image.scaleBy(1000f, 90f, 130f); assertEquals(8f, image.zoom, 0f)
        image.scaleBy(0.0001f, 90f, 130f); assertEquals(1f, image.zoom, 0f)
        assertEquals((400 - image.scaledWidth) / 2, image.x, 0.01f); assertEquals(0f, image.y, 0.01f)
    }
    @Test fun resizeAndSavedPositionPreserveTheZoomAndVisibleCenter() {
        val image = viewport(900, 900, 400, 700)
        image.scaleBy(3f, 200f, 350f); image.panBy(-80f, 40f)
        val saved = image.position()
        image.resize(700, 400)
        assertEquals(saved.zoom, image.zoom, 0f); assertEquals(saved.centerX, image.position().centerX, 0.001f)
        assertEquals(saved.centerY, image.position().centerY, 0.001f)
        val recreated = viewport(900, 900, 700, 400).apply { restore(saved) }
        assertEquals(image.x, recreated.x, 0.01f); assertEquals(image.y, recreated.y, 0.01f)
    }
    @Test fun invalidInputAndAnUnmeasuredViewDoNotProduceInvalidCoordinates() {
        val image = viewport(0, 0, 0, 0)
        image.scaleBy(Float.NaN, 0f, 0f); image.panBy(Float.POSITIVE_INFINITY, 0f)
        image.restore(ImageViewport.Position(Float.NaN, Float.NaN, Float.NaN))
        assertTrue(image.x.isFinite() && image.y.isFinite()); assertEquals(1f, image.zoom, 0f)
        image.image(800, 400); image.resize(400, 700)
        assertEquals(400f, image.scaledWidth, 0.01f); assertEquals(250f, image.y, 0.01f)
    }
}
