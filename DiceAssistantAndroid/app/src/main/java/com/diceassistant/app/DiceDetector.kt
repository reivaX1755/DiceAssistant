package com.diceassistant.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight recognizer for the white, square dice used in the supplied game screenshot.
 * It looks for large bright square regions, then counts dark circular components (pips)
 * inside each region. Everything runs locally on the device.
 */
object DiceDetector {
    data class Reading(val values: List<Int>) {
        val sum: Int get() = values.sum()
    }

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val area: Int
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        val ratio: Float get() = width.toFloat() / height.toFloat()
    }

    fun detect(bitmap: Bitmap): Reading? {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        if (imageWidth < 200 || imageHeight < 350) return null

        val pixels = IntArray(imageWidth * imageHeight)
        bitmap.getPixels(pixels, 0, imageWidth, 0, 0, imageWidth, imageHeight)

        // The game places the dice in the upper-middle part of the screen. This also
        // avoids the answer keypad and most social-app controls around the edges.
        val searchTop = (imageHeight * 0.12f).toInt()
        val searchBottom = (imageHeight * 0.72f).toInt()
        val minSide = (imageWidth * 0.075f).toInt()
        val minFaceArea = (imageWidth * imageHeight * 0.006f).toInt()

        val brightComponents = findComponents(
            pixels = pixels,
            imageWidth = imageWidth,
            left = 0,
            top = searchTop,
            right = imageWidth,
            bottom = searchBottom,
            predicate = { luminance(it) >= 180 },
            minArea = minFaceArea
        )

        val squareFaces = brightComponents.filter { component ->
            component.width >= minSide &&
                component.height >= minSide &&
                component.ratio in 0.78f..1.22f &&
                component.width <= imageWidth * 0.36f &&
                component.height <= imageHeight * 0.20f
        }

        if (squareFaces.size < 4) return null

        // If unrelated square UI elements are also present, the four largest bright
        // square regions are the most likely dice faces.
        val selectedFaces = squareFaces
            .sortedByDescending { it.area }
            .take(4)
            .sortedWith(compareBy<Component> { it.top + it.bottom }.thenBy { it.left })

        val values = ArrayList<Int>(4)
        for (face in selectedFaces) {
            val insetX = (face.width * 0.12f).toInt()
            val insetY = (face.height * 0.12f).toInt()
            val pipComponents = findComponents(
                pixels = pixels,
                imageWidth = imageWidth,
                left = face.left + insetX,
                top = face.top + insetY,
                right = face.right - insetX + 1,
                bottom = face.bottom - insetY + 1,
                predicate = { luminance(it) <= 105 },
                minArea = max(24, (face.width * face.height * 0.0018f).toInt()),
                maxArea = (face.width * face.height * 0.16f).toInt()
            ).filter { pip ->
                pip.ratio in 0.42f..2.4f &&
                    pip.width >= 4 && pip.height >= 4
            }

            val pipCount = pipComponents.size
            if (pipCount !in 1..6) return null
            values.add(pipCount)
        }

        return Reading(values)
    }

    private fun findComponents(
        pixels: IntArray,
        imageWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        predicate: (Int) -> Boolean,
        minArea: Int,
        maxArea: Int = Int.MAX_VALUE
    ): List<Component> {
        val regionWidth = right - left
        val regionHeight = bottom - top
        if (regionWidth <= 0 || regionHeight <= 0) return emptyList()

        val regionSize = regionWidth * regionHeight
        val visited = BooleanArray(regionSize)
        val queue = IntArray(regionSize)
        val result = ArrayList<Component>()

        for (startY in 0 until regionHeight) {
            for (startX in 0 until regionWidth) {
                val startIndex = startY * regionWidth + startX
                if (visited[startIndex]) continue

                val startColor = pixels[(top + startY) * imageWidth + left + startX]
                if (!predicate(startColor)) {
                    visited[startIndex] = true
                    continue
                }

                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true

                var area = 0
                var minX = startX
                var maxX = startX
                var minY = startY
                var maxY = startY

                while (head < tail) {
                    val current = queue[head++]
                    val x = current % regionWidth
                    val y = current / regionWidth
                    area++
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)

                    for (neighborY in max(0, y - 1)..min(regionHeight - 1, y + 1)) {
                        for (neighborX in max(0, x - 1)..min(regionWidth - 1, x + 1)) {
                            if (neighborX == x && neighborY == y) continue
                            val neighborIndex = neighborY * regionWidth + neighborX
                            if (visited[neighborIndex]) continue
                            visited[neighborIndex] = true

                            val color = pixels[
                                (top + neighborY) * imageWidth + left + neighborX
                            ]
                            if (predicate(color)) {
                                queue[tail++] = neighborIndex
                            }
                        }
                    }
                }

                if (area in minArea..maxArea) {
                    result.add(
                        Component(
                            left = left + minX,
                            top = top + minY,
                            right = left + maxX,
                            bottom = top + maxY,
                            area = area
                        )
                    )
                }
            }
        }
        return result
    }

    private fun luminance(color: Int): Int {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }
}
