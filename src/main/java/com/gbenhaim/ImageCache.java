package com.gbenhaim;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.util.LruCache;
import com.jakewharton.DiskLruCache;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ImageCache {
    private Context mContext;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskCache;

    private File mCacheDir;

    private static final int APP_VERSION = 1;

    public ImageCache(Context context, String cacheSubdir, final int maxDiskCacheSize) {
        mContext = context;

        mMemoryCache = new LruCache<String, Bitmap>(getMemoryCacheSize(mContext)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };

        if (cacheSubdir != null) {
            // use the external cache directory if an external storage is mounted
            if (isExternalStorageMounted()) {
                mCacheDir = new File(context.getExternalCacheDir(), cacheSubdir);
            } else {
                mCacheDir = new File(context.getCacheDir(), cacheSubdir);
            }

            // initialize the disk cache
            try {
                mDiskCache = DiskLruCache.open(mCacheDir, 1, 1, maxDiskCacheSize);
            } catch (IOException e) {
                mDiskCache = null;
            }
        }
    }

    private boolean isExternalStorageMounted() {
        return mContext.getExternalCacheDir() != null;
    }

    private int getMemoryCacheSize(Context context) {
        // Get memory class of this device, exceeding this amount will throw an
        // OutOfMemory exception.
        final int memClass = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = 1024 * 1024 * memClass / 8;

        return cacheSize;
    }

    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemoryCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    public Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    synchronized private void addBitmapToDiskCache(String key, InputStream in) throws IOException {
        if (!mCacheDir.exists()) {
            mDiskCache = null;
            return;
        }

        DiskLruCache.Editor editor = mDiskCache.edit(key);

        if (editor == null) return;

        BufferedInputStream bis = new BufferedInputStream(in);

        OutputStream out = editor.newOutputStream(0);

        int readData = 0;
        while(readData >= 0) {
            readData = bis.read();
            out.write(readData);
        }

        editor.commit();
    }

    private Bitmap getBitmapFromDiskCache(String key, BitmapFactory.Options options) throws IOException {
        if (!mCacheDir.exists()) {
            mDiskCache = null;
            return null;
        }

        DiskLruCache.Snapshot snapshot = mDiskCache.get(key);

        Bitmap bitmap = null;

        if (snapshot != null) {
            bitmap = BitmapFactory.decodeStream(new BufferedInputStream(snapshot.getInputStream(0)), null, options);
            snapshot.close();
        }

        return bitmap;
    }

    private boolean isBitmapInDiskCache(String key) throws IOException {
        if (!mCacheDir.exists()) {
            mDiskCache = null;
            return false;
        }

        return mDiskCache.get(key) != null;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            if (width > height) {
                inSampleSize = Math.round((float) height / (float) reqHeight);
            } else {
                inSampleSize = Math.round((float)width / (float)reqWidth);
            }
        }
        return inSampleSize;
    }

    public Bitmap getBitmapFromURL(String bitmapURL, int reqWidth, int reqHeight) throws IOException {
        // try to get the bitmap from the memory cache
        Bitmap bitmap = getBitmapFromMemoryCache(bitmapURL);

        if (bitmap != null) return bitmap;

        if (mDiskCache != null) {
            if (!isBitmapInDiskCache(urlToKey(bitmapURL))) {
                HttpURLConnection urlConnection = null;

                try {
                    URL url = new URL(bitmapURL);
                    urlConnection = (HttpURLConnection) url.openConnection();
                    InputStream in = urlConnection.getInputStream();

                    addBitmapToDiskCache(urlToKey(bitmapURL), in);
                } catch (MalformedURLException e) {

                } finally {
                    if (urlConnection != null) urlConnection.disconnect();
                }
            }

            if (isBitmapInDiskCache(urlToKey(bitmapURL))) {
                final BitmapFactory.Options options = new BitmapFactory.Options();

                // First decode with inJustDecodeBounds=true to check dimensions
                options.inJustDecodeBounds = true;

                getBitmapFromDiskCache(urlToKey(bitmapURL), options);

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

                // Decode bitmap with inSampleSize set
                options.inJustDecodeBounds = false;

                bitmap = getBitmapFromDiskCache(urlToKey(bitmapURL), options);

                // add the bitmap to memory cache
                if (bitmap != null) addBitmapToMemoryCache(bitmapURL, bitmap);
            }
        }

        if (mDiskCache == null) {
            // no disk cache, use only memory cache
            HttpURLConnection urlConnection = null;
            final BitmapFactory.Options options = new BitmapFactory.Options();

            try {
                URL url = new URL(bitmapURL);
                urlConnection = (HttpURLConnection) url.openConnection();
                InputStream in = urlConnection.getInputStream();

                // First decode with inJustDecodeBounds=true to check dimensions
                options.inJustDecodeBounds = true;

                BitmapFactory.decodeStream(new FlushedInputStream(in), null, options);

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

                // Decode bitmap with inSampleSize set
                options.inJustDecodeBounds = false;
            } catch (MalformedURLException e) {

            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }

            try {
                URL url = new URL(bitmapURL);
                urlConnection = (HttpURLConnection) url.openConnection();
                InputStream in = urlConnection.getInputStream();

                bitmap = BitmapFactory.decodeStream(new FlushedInputStream(in), null, options);

                // add bitmap to memory cache
                addBitmapToMemoryCache(bitmapURL, bitmap);
            } catch (MalformedURLException e) {

            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }
        }

        return bitmap;
    }

    public String urlToKey(String url) {
        try {
            // Create MD5 Hash
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(url.getBytes());
            byte messageDigest[] = digest.digest();

            // Create Hex String
            StringBuilder hexString = new StringBuilder();
            for (int i=0; i<messageDigest.length; i++)
                hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    private static class FlushedInputStream extends FilterInputStream {
        public FlushedInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public long skip(long n) throws IOException {
            long totalBytesSkipped = 0L;
            while (totalBytesSkipped < n) {
                long bytesSkipped = in.skip(n - totalBytesSkipped);
                if (bytesSkipped == 0L) {
                    int b = read();
                    if (b < 0) {
                        break;  // we reached EOF
                    } else {
                        bytesSkipped = 1; // we read one byte
                    }
                }
                totalBytesSkipped += bytesSkipped;
            }
            return totalBytesSkipped;
        }
    }

    public void flush()  {
        if (mDiskCache != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mDiskCache.flush();
                    } catch (IOException e) {
                    }
                }
            }).run();
        }
    }
}