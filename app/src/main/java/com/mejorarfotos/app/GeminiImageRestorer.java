package com.mejorarfotos.app;

import android.graphics.Bitmap;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.AspectRatio;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerationConfig;
import com.google.firebase.ai.type.GenerativeBackend;
import com.google.firebase.ai.type.ImageConfig;
import com.google.firebase.ai.type.ImagePart;
import com.google.firebase.ai.type.ImageSize;
import com.google.firebase.ai.type.Part;
import com.google.firebase.ai.type.ResponseModality;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Nano Banana image restoration through Firebase AI Logic and Firebase Auth. */
public final class GeminiImageRestorer {
    private static final String MODEL = "gemini-3.1-flash-image";
    private static volatile ListenableFuture<GenerateContentResponse> activeRequest;

    private GeminiImageRestorer() {}

    public static Bitmap restore(Bitmap targetCrop, Bitmap originalContext) throws Exception {
        Bitmap preparedCrop = ProcessingMemory.fit(targetCrop, 1536);
        Bitmap preparedContext = originalContext == null
                ? null
                : ProcessingMemory.fit(originalContext, 1600);
        try {
            GenerationConfig config = new GenerationConfig.Builder()
                    .setResponseModalities(Arrays.asList(ResponseModality.IMAGE))
                    .setImageConfig(ImageConfig.builder()
                            .setAspectRatio(aspectRatio(targetCrop.getWidth(), targetCrop.getHeight()))
                            .setImageSize(ImageSize.SIZE_2K)
                            .build())
                    .build();
            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel(MODEL, config);
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);

            Content.Builder prompt = new Content.Builder()
                    .addText(prompt(preparedContext != null && originalContext != targetCrop))
                    .addText("Primary image: restore this target crop.")
                    .addImage(preparedCrop);
            if (preparedContext != null && originalContext != targetCrop) {
                prompt.addText("Secondary image: original surrounding context. Use only as reference.")
                        .addImage(preparedContext);
            }

            ListenableFuture<GenerateContentResponse> request = model.generateContent(prompt.build());
            activeRequest = request;
            GenerateContentResponse response = request.get(240, TimeUnit.SECONDS);
            return firstImage(response, targetCrop.getWidth(), targetCrop.getHeight());
        } catch (TimeoutException error) {
            throw new Exception("Gemini tardó demasiado; inténtalo de nuevo", error);
        } catch (ExecutionException error) {
            throw friendly(error.getCause() == null ? error : error.getCause());
        } finally {
            activeRequest = null;
            if (preparedCrop != targetCrop) recycle(preparedCrop);
            if (preparedContext != null
                    && preparedContext != originalContext
                    && preparedContext != preparedCrop) recycle(preparedContext);
        }
    }

    public static void cancelActive() {
        ListenableFuture<GenerateContentResponse> request = activeRequest;
        if (request != null) request.cancel(true);
    }

    static String closestAspectRatio(int width, int height) {
        String[] labels = {"9:16", "2:3", "3:4", "4:5", "1:1", "5:4", "4:3", "3:2", "16:9", "21:9"};
        double[] ratios = {9d / 16, 2d / 3, 3d / 4, 4d / 5, 1d,
                5d / 4, 4d / 3, 3d / 2, 16d / 9, 21d / 9};
        double target = width / (double) Math.max(1, height);
        int best = 0;
        double error = Double.MAX_VALUE;
        for (int i = 0; i < ratios.length; i++) {
            double candidate = Math.abs(Math.log(target / ratios[i]));
            if (candidate < error) {
                error = candidate;
                best = i;
            }
        }
        return labels[best];
    }

    private static AspectRatio aspectRatio(int width, int height) {
        switch (closestAspectRatio(width, height)) {
            case "9:16": return AspectRatio.PORTRAIT_9x16;
            case "2:3": return AspectRatio.PORTRAIT_2x3;
            case "3:4": return AspectRatio.PORTRAIT_3x4;
            case "4:5": return AspectRatio.PORTRAIT_4x5;
            case "5:4": return AspectRatio.LANDSCAPE_5x4;
            case "4:3": return AspectRatio.LANDSCAPE_4x3;
            case "3:2": return AspectRatio.LANDSCAPE_3x2;
            case "16:9": return AspectRatio.LANDSCAPE_16x9;
            case "21:9": return AspectRatio.LANDSCAPE_21x9;
            default: return AspectRatio.SQUARE_1x1;
        }
    }

    private static Bitmap firstImage(
            GenerateContentResponse response, int targetWidth, int targetHeight) throws Exception {
        if (response == null || response.getCandidates().isEmpty()) {
            throw new Exception("Gemini no devolvió una imagen; revisa los filtros de seguridad");
        }
        List<Part> parts = response.getCandidates().get(0).getContent().getParts();
        for (Part part : parts) {
            if (part instanceof ImagePart) {
                Bitmap image = ((ImagePart) part).getImage();
                if (image == null) throw new Exception("Gemini devolvió una imagen inválida");
                return cropToAspect(image, targetWidth / (double) Math.max(1, targetHeight));
            }
        }
        throw new Exception("Gemini no devolvió ninguna imagen");
    }

    private static String prompt(boolean hasContext) {
        return "Restore and upscale the first supplied real photograph conservatively. "
                + (hasContext
                ? "The second image is uncropped context; use it only to preserve geometry, colors and lighting. "
                : "Preserve the complete original framing. ")
                + "Correct motion blur, compression artifacts, noise and lost resolution. Preserve the exact person, identity, pose, apparent age, body proportions, clothing, architecture, perspective, framing and lighting. "
                + "Do not add or remove people or objects. Do not beautify, replace the face, alter anatomy, add hair, glasses, body hair or text unless clearly supported by the source. "
                + "Return exactly one restored photorealistic image with the same composition and aspect ratio, without borders, captions or watermarks.";
    }

    private static Bitmap cropToAspect(Bitmap source, double targetRatio) {
        double ratio = source.getWidth() / (double) source.getHeight();
        int width = source.getWidth();
        int height = source.getHeight();
        if (Math.abs(ratio - targetRatio) < .01) return source;
        if (ratio > targetRatio) width = Math.max(1, (int) Math.round(height * targetRatio));
        else height = Math.max(1, (int) Math.round(width / targetRatio));
        int x = Math.max(0, (source.getWidth() - width) / 2);
        int y = Math.max(0, (source.getHeight() - height) / 2);
        Bitmap cropped = Bitmap.createBitmap(source, x, y, width, height);
        if (cropped != source) recycle(source);
        return cropped;
    }

    private static Exception friendly(Throwable error) {
        String raw = error == null ? "" : String.valueOf(error.getMessage());
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("unauthenticated")) {
            return new Exception("La sesión de Google ha caducado; vuelve a identificarte", error);
        }
        if (lower.contains("429") || lower.contains("quota")) {
            return new Exception("Se alcanzó la cuota de Gemini; inténtalo más tarde", error);
        }
        if (lower.contains("app check") || lower.contains("appcheck")
                || lower.contains("attestation") || lower.contains("integrity token")) {
            return new Exception(
                    "No se pudo verificar la integridad de UGscaler con Google Play", error);
        }
        if (lower.contains("billing") || lower.contains("blaze") || lower.contains("403")) {
            return new Exception("La restauración generativa aún no tiene facturación habilitada", error);
        }
        return new Exception(raw.isEmpty() ? "No se pudo completar la mejora generativa" : raw, error);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
