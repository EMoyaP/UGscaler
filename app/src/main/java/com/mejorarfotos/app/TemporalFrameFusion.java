package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Aligns neighbouring source frames to the selected frame and merges only
 * photometrically consistent pixels. This uses information genuinely present
 * in the video and therefore does not invent detail.
 */
public final class TemporalFrameFusion {
    private static final int RADIUS = 2;
    private static final int MAX_FEATURE_SIDE = 720;

    private TemporalFrameFusion() {}

    public static Bitmap fuse(
            Context context,
            Uri uri,
            VideoFrameProcessor.Selection selection,
            VideoFrameProcessor.Info info) throws Exception {
        Bitmap safeReference = ProcessingMemory.fit(
                selection.bitmap, ProcessingMemory.videoFusionMaxSide(context));
        if (safeReference != selection.bitmap) {
            if (!selection.bitmap.isRecycled()) selection.bitmap.recycle();
            selection = new VideoFrameProcessor.Selection(
                    safeReference, selection.timeUs, selection.frameIndex);
        }
        try {
            return fuseInternal(context, uri, selection, info);
        } catch (OutOfMemoryError memoryError) {
            System.gc();
            return selection.bitmap;
        }
    }

    private static Bitmap fuseInternal(
            Context context,
            Uri uri,
            VideoFrameProcessor.Selection selection,
            VideoFrameProcessor.Info info) throws Exception {
        if (Build.VERSION.SDK_INT < 28
                || selection.frameIndex < 0
                || info.frameCount < 3
                || !OpenCVLoader.initLocal()) {
            return selection.bitmap;
        }

        List<Bitmap> frames = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            for (int offset = -RADIUS; offset <= RADIUS; offset++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Fusión temporal cancelada");
                }
                int index = clamp(selection.frameIndex + offset, 0, info.frameCount - 1);
                if (index == selection.frameIndex) {
                    frames.add(selection.bitmap);
                    offsets.add(offset);
                    continue;
                }
                Bitmap frame = retriever.getFrameAtIndex(index);
                Bitmap safeFrame = frame == null
                        ? null
                        : ProcessingMemory.fit(
                                frame, ProcessingMemory.videoFusionMaxSide(context));
                if (safeFrame != frame && frame != null && !frame.isRecycled()) {
                    frame.recycle();
                }
                frame = safeFrame;
                if (frame != null
                        && frame.getWidth() == selection.bitmap.getWidth()
                        && frame.getHeight() == selection.bitmap.getHeight()) {
                    frames.add(frame);
                    offsets.add(offset);
                }
            }
        } finally {
            retriever.release();
        }

        if (frames.size() < 3) {
            recycleNeighbours(frames, selection.bitmap);
            return selection.bitmap;
        }

        try {
            int referenceIndex = offsets.indexOf(0);
            Bitmap reference = frames.get(referenceIndex);
            List<AlignedFrame> aligned = new ArrayList<>();
            try {
                aligned.add(AlignedFrame.reference(reference));
                for (int i = 0; i < frames.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Fusión temporal cancelada");
                    }
                    if (i == referenceIndex) continue;
                    AlignedFrame candidate = align(frames.get(i), reference);
                    if (candidate != null) aligned.add(candidate);
                }
                if (aligned.size() < 3) return selection.bitmap;
                Bitmap result = merge(reference, aligned);
                if (result != selection.bitmap && !selection.bitmap.isRecycled()) {
                    selection.bitmap.recycle();
                }
                return result;
            } finally {
                releaseAligned(aligned);
            }
        } finally {
            recycleNeighbours(frames, selection.bitmap);
        }
    }

    private static AlignedFrame align(Bitmap candidate, Bitmap reference) {
        Mat candidateRgba = new Mat();
        Mat referenceRgba = new Mat();
        Mat candidateSmall = new Mat();
        Mat referenceSmall = new Mat();
        Mat candidateGray = new Mat();
        Mat referenceGray = new Mat();
        Mat descriptorsCandidate = new Mat();
        Mat descriptorsReference = new Mat();
        Mat candidateMask = new Mat();
        Mat referenceMask = new Mat();
        MatOfKeyPoint candidateKeys = new MatOfKeyPoint();
        MatOfKeyPoint referenceKeys = new MatOfKeyPoint();
        MatOfPoint2f sourceMat = new MatOfPoint2f();
        MatOfPoint2f targetMat = new MatOfPoint2f();
        MatOfByte inliers = new MatOfByte();
        Mat homography = null;
        Mat warped = new Mat();
        Mat valid = null;
        Mat warpedValid = new Mat();
        ORB orb = null;
        DescriptorMatcher matcher = null;
        List<MatOfDMatch> matches = new ArrayList<>();
        try {
            Utils.bitmapToMat(candidate, candidateRgba);
            Utils.bitmapToMat(reference, referenceRgba);
            double scale = Math.min(
                    1.0, MAX_FEATURE_SIDE / (double) Math.max(reference.getWidth(), reference.getHeight()));
            Size featureSize = new Size(
                    Math.max(1, Math.round(reference.getWidth() * scale)),
                    Math.max(1, Math.round(reference.getHeight() * scale)));
            Imgproc.resize(candidateRgba, candidateSmall, featureSize, 0, 0, Imgproc.INTER_AREA);
            Imgproc.resize(referenceRgba, referenceSmall, featureSize, 0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(candidateSmall, candidateGray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(referenceSmall, referenceGray, Imgproc.COLOR_RGBA2GRAY);

            orb = ORB.create(1400);
            orb.detectAndCompute(
                    candidateGray, candidateMask, candidateKeys, descriptorsCandidate);
            orb.detectAndCompute(
                    referenceGray, referenceMask, referenceKeys, descriptorsReference);
            if (descriptorsCandidate.empty() || descriptorsReference.empty()) return null;

            matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
            matcher.knnMatch(descriptorsCandidate, descriptorsReference, matches, 2);
            KeyPoint[] sourceKeys = candidateKeys.toArray();
            KeyPoint[] targetKeys = referenceKeys.toArray();
            List<Point> sourcePoints = new ArrayList<>();
            List<Point> targetPoints = new ArrayList<>();
            for (MatOfDMatch pair : matches) {
                DMatch[] pairValues = pair.toArray();
                if (pairValues.length >= 2 && pairValues[0].distance < pairValues[1].distance * .76f) {
                    sourcePoints.add(sourceKeys[pairValues[0].queryIdx].pt);
                    targetPoints.add(targetKeys[pairValues[0].trainIdx].pt);
                }
            }
            if (sourcePoints.size() < 18) return null;

            sourceMat.fromList(sourcePoints);
            targetMat.fromList(targetPoints);
            homography = Calib3d.findHomography(
                    sourceMat, targetMat, Calib3d.RANSAC, 3.0, inliers, 2000, .995);
            int inlierCount = Core.countNonZero(inliers);
            if (homography.empty() || inlierCount < 12) return null;

            if (scale < 1.0) {
                double[] h = new double[9];
                homography.get(0, 0, h);
                h[2] /= scale;
                h[5] /= scale;
                h[6] *= scale;
                h[7] *= scale;
                homography.put(0, 0, h);
            }

            valid = Mat.ones(
                    candidate.getHeight(),
                    candidate.getWidth(),
                    org.opencv.core.CvType.CV_8UC1);
            Size output = new Size(reference.getWidth(), reference.getHeight());
            Imgproc.warpPerspective(
                    candidateRgba, warped, homography, output, Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT, Scalar.all(0));
            Imgproc.warpPerspective(
                    valid, warpedValid, homography, output, Imgproc.INTER_NEAREST,
                    Core.BORDER_CONSTANT, Scalar.all(0));

            Bitmap warpedBitmap = Bitmap.createBitmap(
                    reference.getWidth(), reference.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(warped, warpedBitmap);
            byte[] mask = new byte[reference.getWidth() * reference.getHeight()];
            warpedValid.get(0, 0, mask);
            return new AlignedFrame(warpedBitmap, mask, false);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            for (MatOfDMatch pair : matches) pair.release();
            if (matcher != null) matcher.clear();
            if (orb != null) orb.clear();
            candidateRgba.release();
            referenceRgba.release();
            candidateSmall.release();
            referenceSmall.release();
            candidateGray.release();
            referenceGray.release();
            descriptorsCandidate.release();
            descriptorsReference.release();
            candidateMask.release();
            referenceMask.release();
            candidateKeys.release();
            referenceKeys.release();
            sourceMat.release();
            targetMat.release();
            inliers.release();
            if (homography != null) homography.release();
            warped.release();
            if (valid != null) valid.release();
            warpedValid.release();
        }
    }

    private static Bitmap merge(Bitmap reference, List<AlignedFrame> frames)
            throws InterruptedException {
        int width = reference.getWidth();
        int height = reference.getHeight();
        int[][] rows = new int[frames.size()][width];
        int[] outputRow = new int[width];
        Bitmap fused = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) {
                fused.recycle();
                throw new InterruptedException("Fusión temporal cancelada");
            }
            for (int frame = 0; frame < frames.size(); frame++) {
                frames.get(frame).bitmap.getPixels(
                        rows[frame], 0, width, 0, y, width, 1);
            }
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int base = rows[0][x];
                int baseR = android.graphics.Color.red(base);
                int baseG = android.graphics.Color.green(base);
                int baseB = android.graphics.Color.blue(base);
                double sumR = baseR, sumG = baseG, sumB = baseB, weightSum = 1.0;
                for (int frame = 1; frame < frames.size(); frame++) {
                    if ((frames.get(frame).mask[rowOffset + x] & 0xff) == 0) continue;
                    int color = rows[frame][x];
                    int r = android.graphics.Color.red(color);
                    int g = android.graphics.Color.green(color);
                    int b = android.graphics.Color.blue(color);
                    double difference = Math.abs(r - baseR) * .30
                            + Math.abs(g - baseG) * .59 + Math.abs(b - baseB) * .11;
                    if (difference > 34.0) continue;
                    double weight =
                            .72
                                    * Math.exp(
                                            -(difference * difference)
                                                    / (2.0 * 18.0 * 18.0));
                    sumR += r * weight;
                    sumG += g * weight;
                    sumB += b * weight;
                    weightSum += weight;
                }
                outputRow[x] = android.graphics.Color.rgb(
                        clamp((int) Math.round(sumR / weightSum), 0, 255),
                        clamp((int) Math.round(sumG / weightSum), 0, 255),
                        clamp((int) Math.round(sumB / weightSum), 0, 255));
            }
            fused.setPixels(outputRow, 0, width, 0, y, width, 1);
        }
        return fused;
    }

    private static void releaseAligned(List<AlignedFrame> frames) {
        for (AlignedFrame frame : frames) {
            if (!frame.reference && !frame.bitmap.isRecycled()) frame.bitmap.recycle();
        }
    }

    private static void recycleNeighbours(List<Bitmap> frames, Bitmap reference) {
        for (Bitmap frame : frames) {
            if (frame != reference && !frame.isRecycled()) frame.recycle();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class AlignedFrame {
        final Bitmap bitmap;
        final byte[] mask;
        final boolean reference;

        AlignedFrame(Bitmap bitmap, byte[] mask, boolean reference) {
            this.bitmap = bitmap;
            this.mask = mask;
            this.reference = reference;
        }

        static AlignedFrame reference(Bitmap bitmap) {
            byte[] mask = new byte[bitmap.getWidth() * bitmap.getHeight()];
            java.util.Arrays.fill(mask, (byte) 255);
            return new AlignedFrame(bitmap, mask, true);
        }
    }
}
