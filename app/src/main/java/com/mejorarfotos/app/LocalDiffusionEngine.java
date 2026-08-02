package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Java/JNA bridge to the 16 KB-aligned stable-diffusion.cpp Android runtime. */
final class LocalDiffusionEngine {
    interface ProgressListener {
        void onProgress(int step, int total);
    }

    private interface SdApi extends Library {
        int get_num_physical_cores();
        void sd_set_progress_callback(ProgressCallback callback, Pointer data);
        Pointer new_sd_ctx(
                String model, String clipL, String clipG, String t5xxl,
                String diffusionModel, String vae, String taesd, String controlNet,
                String loraDirectory, String embeddingsDirectory, String stackedIdDirectory,
                byte vaeDecodeOnly, byte vaeTiling, byte freeParamsImmediately,
                int threads, int weightType, int rngType, int schedule,
                byte clipOnCpu, byte controlOnCpu, byte vaeOnCpu, byte flashAttention);
        void free_sd_ctx(Pointer context);
        Pointer txt2img(
                Pointer context, String prompt, String negativePrompt, int clipSkip,
                float cfgScale, float guidance, float eta, int width, int height,
                int sampleMethod, int steps, long seed, int batchCount,
                Pointer controlImage, float controlStrength, float styleStrength,
                byte normalizeInput, String inputIdImages, Pointer skipLayers,
                long skipLayersCount, float slgScale, float skipLayerStart,
                float skipLayerEnd);
        Pointer img2img(
                Pointer context, SdImage.ByValue input, SdImage.ByValue mask,
                String prompt, String negativePrompt, int clipSkip,
                float cfgScale, float guidance, float eta, int width, int height,
                int sampleMethod, int steps, float strength, long seed, int batchCount,
                Pointer controlImage, float controlStrength, float styleStrength,
                byte normalizeInput, String inputIdImages, Pointer skipLayers,
                long skipLayersCount, float slgScale, float skipLayerStart,
                float skipLayerEnd);
    }

    private interface ProgressCallback extends Callback {
        void invoke(int step, int steps, float seconds, Pointer data);
    }

    public static class SdImage extends Structure {
        public int width;
        public int height;
        public int channel;
        public Pointer data;
        public Pointer userdata;

        public SdImage() {}
        public SdImage(Pointer pointer) { super(pointer); read(); }

        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("width", "height", "channel", "data", "userdata");
        }

        public static class ByValue extends SdImage implements Structure.ByValue {
            public ByValue() { super(); }
        }
    }

    private static final Object LOCK = new Object();
    private static ProgressCallback progressCallback;

    private LocalDiffusionEngine() {}

    static Bitmap generate(
            Context context,
            String prompt,
            String negativePrompt,
            Bitmap initial,
            int size,
            float strength,
            long seed,
            ProgressListener listener) throws Exception {
        File model = GenerativeModelRepository.modelFile(context);
        if (!model.isFile()) throw new Exception("Descarga primero el modelo generativo");
        synchronized (LOCK) {
            SdApi api = loadRuntime();
            progressCallback = (step, total, seconds, data) -> {
                if (listener != null) listener.onProgress(step, total);
            };
            api.sd_set_progress_callback(progressCallback, Pointer.NULL);
            int threads = Math.max(2, Math.min(8, api.get_num_physical_cores()));
            Pointer diffusion = api.new_sd_ctx(
                    model.getAbsolutePath(), "", "", "", "", "", "", "",
                    "/", "", "", b(false), b(true), b(false),
                    // SD_TYPE_Q4_0 is index 8 in this runtime. Keeping the VAE on
                    // the selected backend avoids an unnecessary CPU copy.
                    threads, 8, 0, 0,
                    b(false), b(false), b(false), b(true));
            if (diffusion == null || Pointer.nativeValue(diffusion) == 0L) {
                throw new Exception("El modelo no pudo iniciarse en este teléfono");
            }
            try {
                Pointer result = initial == null
                        ? api.txt2img(
                                diffusion, prompt, negativePrompt, 1,
                                1.5f, 1f, 0f, size, size,
                                9, 6, seed, 1, Pointer.NULL,
                                0f, 0f, b(false), "", Pointer.NULL,
                                0L, 0f, .01f, .2f)
                        : runImg2Img(api, diffusion, initial, prompt, negativePrompt,
                                size, strength, seed);
                return decodeAndFree(result);
            } finally {
                api.free_sd_ctx(diffusion);
                progressCallback = null;
                System.gc();
            }
        }
    }

    private static Pointer runImg2Img(
            SdApi api, Pointer context, Bitmap original, String prompt,
            String negativePrompt, int size, float strength, long seed) {
        Bitmap square = centerCrop(original, size);
        int pixelCount = size * size;
        Memory rgb = new Memory(pixelCount * 3L);
        Memory maskData = new Memory(pixelCount);
        byte[] packed = new byte[pixelCount * 3];
        int[] row = new int[size];
        int target = 0;
        for (int y = 0; y < size; y++) {
            square.getPixels(row, 0, size, 0, y, size, 1);
            for (int color : row) {
                packed[target++] = (byte) Color.red(color);
                packed[target++] = (byte) Color.green(color);
                packed[target++] = (byte) Color.blue(color);
            }
        }
        rgb.write(0, packed, 0, packed.length);
        maskData.setMemory(0, pixelCount, (byte) 0xff);
        SdImage.ByValue input = new SdImage.ByValue();
        input.width = size; input.height = size; input.channel = 3; input.data = rgb;
        input.userdata = Pointer.NULL; input.write();
        SdImage.ByValue mask = new SdImage.ByValue();
        mask.width = size; mask.height = size; mask.channel = 1; mask.data = maskData;
        mask.userdata = Pointer.NULL; mask.write();
        Pointer result = api.img2img(
                context, input, mask, prompt, negativePrompt, 1,
                1.5f, 1f, 0f, size, size, 9, 6,
                strength, seed, 1, Pointer.NULL, 0f, 0f,
                b(false), "", Pointer.NULL, 0L, 0f, .01f, .2f);
        square.recycle();
        return result;
    }

    private static Bitmap decodeAndFree(Pointer result) throws Exception {
        if (result == null || Pointer.nativeValue(result) == 0L) {
            throw new Exception("Stable Diffusion no pudo generar la imagen");
        }
        SdImage image = new SdImage(result);
        try {
            if (image.width < 64 || image.height < 64 || image.channel != 3
                    || image.data == null) {
                throw new Exception("La salida generativa no es válida");
            }
            byte[] bytes = image.data.getByteArray(
                    0, Math.multiplyExact(Math.multiplyExact(image.width, image.height), 3));
            int[] colors = new int[image.width * image.height];
            for (int pixel = 0, source = 0; pixel < colors.length; pixel++) {
                colors[pixel] = Color.rgb(
                        bytes[source++] & 0xff,
                        bytes[source++] & 0xff,
                        bytes[source++] & 0xff);
            }
            Bitmap bitmap = Bitmap.createBitmap(
                    image.width, image.height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(colors, 0, image.width, 0, 0, image.width, image.height);
            return bitmap;
        } finally {
            if (image.data != null) Native.free(Pointer.nativeValue(image.data));
            Native.free(Pointer.nativeValue(result));
        }
    }

    private static SdApi loadRuntime() throws Exception {
        try {
            return Native.load("stable-diffusion_vulkan", SdApi.class);
        } catch (Throwable vulkanError) {
            try {
                return Native.load("stable-diffusion", SdApi.class);
            } catch (Throwable cpuError) {
                throw new Exception("El motor generativo no es compatible con este dispositivo", cpuError);
            }
        }
    }

    private static Bitmap centerCrop(Bitmap source, int size) {
        float scale = Math.max(size / (float) source.getWidth(), size / (float) source.getHeight());
        Bitmap enlarged = Bitmap.createScaledBitmap(source,
                Math.max(size, Math.round(source.getWidth() * scale)),
                Math.max(size, Math.round(source.getHeight() * scale)), true);
        int left = Math.max(0, (enlarged.getWidth() - size) / 2);
        int top = Math.max(0, (enlarged.getHeight() - size) / 2);
        Bitmap square = Bitmap.createBitmap(enlarged, left, top, size, size);
        if (square != enlarged) enlarged.recycle();
        return square;
    }

    private static byte b(boolean value) { return (byte) (value ? 1 : 0); }
}
