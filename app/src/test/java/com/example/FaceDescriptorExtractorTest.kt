package com.example

import com.example.data.FaceTemplateStore
import com.example.domain.biometrics.DescriptorExtractionResult
import com.example.domain.biometrics.FaceDescriptorExtractor
import com.example.domain.biometrics.FacialGeometryInput
import com.example.domain.biometrics.Point2D
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDescriptorExtractorTest {

    private fun samplePersonA(
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        angleDeg: Float = 0f
    ): FacialGeometryInput {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val cosA = cos(rad)
        val sinA = sin(rad)
        fun p(x: Float, y: Float): Point2D {
            val sx = x * scale
            val sy = y * scale
            val rx = sx * cosA - sy * sinA + offsetX
            val ry = sx * sinA + sy * cosA + offsetY
            return Point2D(rx, ry)
        }
        // Person A: slightly narrower jaw, longer nose, higher cheekbones
        return FacialGeometryInput(
            leftEye = p(-50f, 0f),
            rightEye = p(50f, 0f),
            noseBase = p(0f, 70f),
            mouthLeft = p(-36f, 114f),
            mouthRight = p(36f, 114f),
            mouthBottom = p(0f, 132f),
            leftCheek = p(-62f, 54f),
            rightCheek = p(62f, 54f),
            leftEar = p(-104f, 20f),
            rightEar = p(104f, 20f),
            eulerY = 2f,
            eulerZ = angleDeg,
            boundingWidth = 190f * scale,
            boundingHeight = 250f * scale
        )
    }

    private fun samplePersonB(): FacialGeometryInput {
        // Person B: wider face/jaw, shorter nose, wider mouth
        return FacialGeometryInput(
            leftEye = Point2D(-50f, 0f),
            rightEye = Point2D(50f, 0f),
            noseBase = Point2D(0f, 48f),
            mouthLeft = Point2D(-52f, 96f),
            mouthRight = Point2D(52f, 96f),
            mouthBottom = Point2D(0f, 110f),
            leftCheek = Point2D(-80f, 72f),
            rightCheek = Point2D(80f, 72f),
            leftEar = Point2D(-125f, 34f),
            rightEar = Point2D(125f, 34f),
            eulerY = 0f,
            eulerZ = 0f,
            boundingWidth = 235f,
            boundingHeight = 225f
        )
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    @Test
    fun testScaleTranslationAndRollInvariance_SamePersonMatchesStrongly() {
        val base = FaceDescriptorExtractor.extract(samplePersonA()) as DescriptorExtractionResult.Success
        val scaledShiftedTilted = FaceDescriptorExtractor.extract(
            samplePersonA(scale = 1.75f, offsetX = 420f, offsetY = 260f, angleDeg = 14f)
        ) as DescriptorExtractionResult.Success

        assertEquals(FaceDescriptorExtractor.DESCRIPTOR_DIMENSIONS, base.vector.size)
        val sim = cosine(base.vector, scaledShiftedTilted.vector)
        assertTrue("Expected similarity >= 0.95 for same person transformed, got $sim", sim >= 0.95f)
    }

    @Test
    fun testDistinctFaceProportions_BelowStrictThreshold() {
        val personA = FaceDescriptorExtractor.extract(samplePersonA()) as DescriptorExtractionResult.Success
        val personB = FaceDescriptorExtractor.extract(samplePersonB()) as DescriptorExtractionResult.Success

        val sim = cosine(personA.vector, personB.vector)
        assertTrue(
            "Expected distinct person similarity < ${FaceTemplateStore.MATCH_THRESHOLD}, got $sim",
            sim < FaceTemplateStore.MATCH_THRESHOLD
        )
    }

    @Test
    fun testQualityGate_RejectsExtremeYawAndMissingLandmarks() {
        val missingNose = FaceDescriptorExtractor.extract(
            samplePersonA().copy(noseBase = null)
        )
        assertTrue(missingNose is DescriptorExtractionResult.Rejected)

        val extremeYaw = FaceDescriptorExtractor.extract(
            samplePersonA().copy(eulerY = 38f)
        )
        assertTrue(extremeYaw is DescriptorExtractionResult.Rejected)

        val tinyFace = FaceDescriptorExtractor.extract(
            samplePersonA(scale = 0.05f)
        )
        assertTrue(tinyFace is DescriptorExtractionResult.Rejected)
    }

    @Test
    fun testMultiPoseAveraging_ProducesUnitNormalizedCentroid() {
        val p1 = (FaceDescriptorExtractor.extract(samplePersonA()) as DescriptorExtractionResult.Success).vector
        val p2 = (FaceDescriptorExtractor.extract(samplePersonA(angleDeg = -8f)) as DescriptorExtractionResult.Success).vector
        val p3 = (FaceDescriptorExtractor.extract(samplePersonA(angleDeg = 8f)) as DescriptorExtractionResult.Success).vector

        val centroid = FaceDescriptorExtractor.averageAndNormalize(listOf(p1, p2, p3))
        assertNotNull(centroid)
        assertEquals(FaceDescriptorExtractor.DESCRIPTOR_DIMENSIONS, centroid!!.size)
        val norm = sqrt(centroid.fold(0f) { acc, v -> acc + v * v })
        assertEquals(1.0f, norm, 0.001f)
    }
}
