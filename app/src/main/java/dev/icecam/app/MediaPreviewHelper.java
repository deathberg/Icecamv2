package dev.icecam.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import java.io.File;

/** In-app preview thumbnails for MP4 and images (does not affect camera inject). */
public final class MediaPreviewHelper {
    private MediaPreviewHelper() {}

    public static Bitmap loadPreviewFrame(String path, int maxW, int maxH) {
        if (path == null || path.isEmpty()) return null;
        if (MediaTransformer.isImagePath(path)) return decodeImage(path, maxW, maxH);
        if (MediaTransformer.isVideoPath(path)) return decodeVideoFrame(path, maxW, maxH);
        return null;
    }

    public static long videoDurationUs(String path) {
        if (path == null || !MediaTransformer.isVideoPath(path)) return 0L;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null) return 0L;
            return Long.parseLong(d) * 1000L;
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap decodeVideoFrame(String path, int maxW, int maxH) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            return scaleDown(frame, maxW, maxH);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap decodeImage(String path, int maxW, int maxH) {
        try {
            if (!new File(path).exists()) return null;
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, probe);
            if (probe.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(probe.outWidth / sample, probe.outHeight / sample) > Math.max(maxW, maxH)) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(path, opt);
            return bm == null ? null : scaleDown(bm, maxW, maxH);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        float scale = Math.min(1f, Math.min((float) maxW / w, (float) maxH / h));
        if (scale >= 0.999f) return src;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        Bitmap out = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (out != src) src.recycle();
        return out;
    }
}
