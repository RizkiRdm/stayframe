package com.example.domain.biometrics

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class Point2D(val x: Float, val y: Float)

data class FacialGeometryInput(
    val leftEye: Point2D?,
    val rightEye: Point2D?,
    val noseBase: Point2D?,
    val mouthLeft: Point2D?,
    val mouthRight: Point2D?,
    val mouthBottom: Point2D?,
    val leftCheek: Point2D? = null,
    val rightCheek: Point2D? = null,
    val leftEar: Point2D? = null,
    val rightEar: Point2D? = null,
    val faceOvalPoints: List<Point2D> = emptyList(),
    val leftEyebrowPoints: List<Point2D> = emptyList(),
    val rightEyebrowPoints: List<Point2D> = emptyList(),
    val eulerY: Float = 0f,
    val eulerZ: Float = 0f,
    val boundingWidth: Float = 0f,
    val boundingHeight: Float = 0f
)

sealed class DescriptorExtractionResult {
    data class Success(val vector: FloatArray, val qualityHint: String) : DescriptorExtractionResult()
    data class Rejected(val reason: String) : DescriptorExtractionResult()
}

/**
 * Extracts a 24-dimensional scale-, translation-, and roll-normalized facial geometry
 * descriptor from ML Kit landmarks & contours, mean-centered against canonical anthropometric
 * proportions so individual facial differences yield discriminative cosine similarities.
 */
object FaceDescriptorExtractor {

    const val DESCRIPTOR_DIMENSIONS = 24
    const val MAX_EULER_ANGLE_DEG = 25f
    const val MIN_INTER_OCULAR_PX = 12f
    const val RECOMMENDED_MATCH_THRESHOLD = 0.72f

    // Canonical anthropometric means (origin = eye midpoint, scale = inter-ocular distance)
    private val canonicalMeans = floatArrayOf(
        0.00f, 0.58f,   // noseBase (x, y)
        -0.42f, 1.08f,  // mouthLeft (x, y)
        0.42f, 1.08f,   // mouthRight (x, y)
        0.00f, 1.24f,   // mouthBottom (x, y)
        -0.68f, 0.62f,  // leftCheek (x, y)
        0.68f, 0.62f,   // rightCheek (x, y)
        -1.12f, 0.25f,  // leftEar (x, y)
        1.12f, 0.25f,   // rightEar (x, y)
        0.84f,          // mouthWidth / IOD
        1.15f,          // eyeMid to mouthBottom / IOD
        0.58f,          // eyeMid to noseBase / IOD
        0.52f,          // noseBase to mouthBottom / IOD
        1.36f,          // cheekSpan / IOD
        0.78f,          // faceWidth / faceHeight
        0.28f,          // brow-to-eye vertical offset / IOD
        1.55f           // jaw-chin height / IOD
    )

    fun extractFromMlKitFace(face: Face): DescriptorExtractionResult {
        val input = FacialGeometryInput(
            leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { Point2D(it.x, it.y) },
            rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { Point2D(it.x, it.y) },
            noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.let { Point2D(it.x, it.y) },
            mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position?.let { Point2D(it.x, it.y) },
            mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position?.let { Point2D(it.x, it.y) },
            mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.let { Point2D(it.x, it.y) },
            leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position?.let { Point2D(it.x, it.y) },
            rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position?.let { Point2D(it.x, it.y) },
            leftEar = face.getLandmark(FaceLandmark.LEFT_EAR)?.position?.let { Point2D(it.x, it.y) },
            rightEar = face.getLandmark(FaceLandmark.RIGHT_EAR)?.position?.let { Point2D(it.x, it.y) },
            faceOvalPoints = face.getContour(FaceContour.FACE)?.points?.map { Point2D(it.x, it.y) } ?: emptyList(),
            leftEyebrowPoints = face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points?.map { Point2D(it.x, it.y) } ?: emptyList(),
            rightEyebrowPoints = face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points?.map { Point2D(it.x, it.y) } ?: emptyList(),
            eulerY = face.headEulerAngleY,
            eulerZ = face.headEulerAngleZ,
            boundingWidth = face.boundingBox.width().toFloat(),
            boundingHeight = face.boundingBox.height().toFloat()
        )
        return extract(input)
    }

    fun extract(input: FacialGeometryInput, enforcePoseLimit: Boolean = true): DescriptorExtractionResult {
        val leftEye = input.leftEye
        val rightEye = input.rightEye
        val noseBase = input.noseBase
        val mouthBottom = input.mouthBottom

        if (leftEye == null || rightEye == null || noseBase == null || mouthBottom == null) {
            return DescriptorExtractionResult.Rejected("Center full face clearly in frame")
        }

        if (enforcePoseLimit && (kotlin.math.abs(input.eulerY) > MAX_EULER_ANGLE_DEG || kotlin.math.abs(input.eulerZ) > MAX_EULER_ANGLE_DEG)) {
            return DescriptorExtractionResult.Rejected("Hold head steadier facing camera")
        }

        val dx = rightEye.x - leftEye.x
        val dy = rightEye.y - leftEye.y
        val iod = hypot(dx, dy)
        if (iod < MIN_INTER_OCULAR_PX) {
            return DescriptorExtractionResult.Rejected("Move slightly closer to camera")
        }

        val midX = (leftEye.x + rightEye.x) / 2f
        val midY = (leftEye.y + rightEye.y) / 2f
        val rollRad = atan2(dy, dx)
        val cosR = cos(-rollRad)
        val sinR = sin(-rollRad)

        fun normalize(pt: Point2D): Point2D {
            val tx = pt.x - midX
            val ty = pt.y - midY
            val rx = tx * cosR - ty * sinR
            val ry = tx * sinR + ty * cosR
            return Point2D(rx / iod, ry / iod)
        }

        val nNose = normalize(noseBase)
        val nMouthBottom = normalize(mouthBottom)
        val nMouthLeft = input.mouthLeft?.let(::normalize) ?: Point2D(nMouthBottom.x - 0.42f, nMouthBottom.y - 0.16f)
        val nMouthRight = input.mouthRight?.let(::normalize) ?: Point2D(nMouthBottom.x + 0.42f, nMouthBottom.y - 0.16f)
        val nLeftCheek = input.leftCheek?.let(::normalize) ?: Point2D(-0.68f, nNose.y)
        val nRightCheek = input.rightCheek?.let(::normalize) ?: Point2D(0.68f, nNose.y)
        val nLeftEar = input.leftEar?.let(::normalize) ?: Point2D(-1.12f, 0.25f)
        val nRightEar = input.rightEar?.let(::normalize) ?: Point2D(1.12f, 0.25f)

        val mouthWidth = hypot(nMouthRight.x - nMouthLeft.x, nMouthRight.y - nMouthLeft.y)
        val eyeToMouth = hypot(nMouthBottom.x, nMouthBottom.y)
        val eyeToNose = hypot(nNose.x, nNose.y)
        val noseToMouth = hypot(nMouthBottom.x - nNose.x, nMouthBottom.y - nNose.y)
        val cheekSpan = hypot(nRightCheek.x - nLeftCheek.x, nRightCheek.y - nLeftCheek.y)

        val faceAspect = if (input.boundingHeight > 0f && input.boundingWidth > 0f) {
            input.boundingWidth / input.boundingHeight
        } else {
            0.78f
        }

        val browOffset = if (input.leftEyebrowPoints.isNotEmpty() || input.rightEyebrowPoints.isNotEmpty()) {
            val allBrows = (input.leftEyebrowPoints + input.rightEyebrowPoints).map(::normalize)
            val avgY = allBrows.map { it.y }.average().toFloat()
            kotlin.math.abs(avgY)
        } else {
            0.28f
        }

        val jawHeight = if (input.faceOvalPoints.isNotEmpty()) {
            val maxOvalY = input.faceOvalPoints.map(::normalize).maxOf { it.y }
            maxOvalY.coerceIn(0.8f, 2.5f)
        } else {
            nMouthBottom.y + 0.31f
        }

        val rawFeatures = floatArrayOf(
            nNose.x, nNose.y,
            nMouthLeft.x, nMouthLeft.y,
            nMouthRight.x, nMouthRight.y,
            nMouthBottom.x, nMouthBottom.y,
            nLeftCheek.x, nLeftCheek.y,
            nRightCheek.x, nRightCheek.y,
            nLeftEar.x, nLeftEar.y,
            nRightEar.x, nRightEar.y,
            mouthWidth,
            eyeToMouth,
            eyeToNose,
            noseToMouth,
            cheekSpan,
            faceAspect,
            browOffset,
            jawHeight
        )

        // Mean-center against canonical human proportions so cosine similarity discriminates individuals
        val centered = FloatArray(DESCRIPTOR_DIMENSIONS) { i ->
            rawFeatures[i] - canonicalMeans[i]
        }

        val normalized = l2Normalize(centered)
        return DescriptorExtractionResult.Success(
            vector = normalized,
            qualityHint = "Landmarks aligned"
        )
    }

    fun averageAndNormalize(samples: List<FloatArray>): FloatArray? {
        if (samples.isEmpty()) return null
        val dim = samples[0].size
        if (dim == 0 || samples.any { it.size != dim }) return null
        val sum = FloatArray(dim)
        for (sample in samples) {
            for (i in 0 until dim) {
                sum[i] += sample[i]
            }
        }
        val count = samples.size.toFloat()
        for (i in 0 until dim) {
            sum[i] /= count
        }
        return l2Normalize(sum)
    }

    fun l2Normalize(vector: FloatArray): FloatArray {
        var normSq = 0.0
        for (v in vector) {
            normSq += v * v
        }
        val norm = sqrt(normSq).toFloat()
        if (norm <= 1e-6f) {
            // Avoid zero-vector degeneracy when input happens to match canonical mean exactly
            val fallback = vector.copyOf()
            if (fallback.isNotEmpty()) fallback[0] = 1f
            return fallback
        }
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }
}
