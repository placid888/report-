package com.factory.report;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class PhotoHelper {

    private PhotoHelper() {}

    /**
     * Compress and auto-orient a captured JPEG file according to the chosen quality level.
     */
    public static void processCapturedPhoto(File file, String imgQuality) throws IOException {
        if (file == null || !file.exists()) return;

        int rotation = getExifRotation(file);
        boolean needsResize = !"高畫質".equals(imgQuality);
        if (!needsResize && rotation == 0) {
            return;
        }

        int maxDim = "中畫質".equals(imgQuality) ? 1280 : ("低畫質".equals(imgQuality) ? 800 : 2048);
        int jpegQuality = "高畫質".equals(imgQuality) ? 92 : ("中畫質".equals(imgQuality) ? 85 : 78);

        Bitmap bitmap = decodeSampledBitmap(file, maxDim, maxDim);
        if (bitmap == null) return;

        Bitmap finalBmp = bitmap;
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            finalBmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (finalBmp != bitmap) {
                bitmap.recycle();
            }
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            finalBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, fos);
        } finally {
            finalBmp.recycle();
        }
    }

    /**
     * Decode an upright, memory-safe thumbnail or preview bitmap.
     */
    public static Bitmap decodeUprightBitmap(File file, int reqWidth, int reqHeight) {
        if (file == null || !file.exists()) return null;
        try {
            Bitmap bmp = decodeSampledBitmap(file, reqWidth, reqHeight);
            if (bmp == null) return null;
            int rotation = getExifRotation(file);
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                if (rotated != bmp) {
                    bmp.recycle();
                }
                return rotated;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampledBitmap(File file, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);

        int sampleSize = 1;
        int height = options.outHeight;
        int width = options.outWidth;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / sampleSize) >= reqHeight && (halfWidth / sampleSize) >= reqWidth) {
                sampleSize *= 2;
            }
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, sampleSize);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static int getExifRotation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public static void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public static int cleanUpOldFiles(File dir, int autoDeleteDays) {
        if (dir == null || !dir.exists() || autoDeleteDays <= 0) return 0;
        long thresholdMillis = System.currentTimeMillis() - ((long) autoDeleteDays * 24L * 60L * 60L * 1000L);
        File[] files = dir.listFiles();
        int deletedCount = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.lastModified() < thresholdMillis) {
                    if (f.delete()) deletedCount++;
                }
            }
        }
        return deletedCount;
    }
}
