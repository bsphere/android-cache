package com.gbenhaim;

import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.widget.ImageView;

import java.io.IOException;
import java.lang.ref.WeakReference;

public class ImageDownloader {
    private ImageCache mCache;
    private int reqWidth;
    private int reqHeight;

    public void download(String url, ImageView imageView, ImageCache imageCache, int reqHeight, int reqWidth,
                         int emptyResId) {
        mCache = imageCache;
        this.reqHeight = reqHeight;
        this.reqWidth = reqWidth;

        if (url == null) {
            if (emptyResId != 0) {
                imageView.setImageResource(emptyResId);    
            } else {
                imageView.setImageBitmap(null);
            }
            
            imageView.setTag(null);
            return;
        }

        if (cancelPotentialDownload(url, imageView)) {
            BitmapDownloaderTask task = new BitmapDownloaderTask(imageView);

            AsyncTag tag = new AsyncTag(task);
            imageView.setTag(tag);

            if (emptyResId != 0) {
                imageView.setImageResource(emptyResId);    
            } else {
                imageView.setImageBitmap(null);
            }
            
            task.execute(url);
        }
    }

    private class BitmapDownloaderTask extends AsyncTask<String, Void, Bitmap> {
        private String url;
        private WeakReference<ImageView> imageViewReference;

        public BitmapDownloaderTask(ImageView imageView) {
            imageViewReference = new WeakReference<ImageView>(imageView);
        }

        @Override
        protected Bitmap doInBackground(String... strings) {
            Bitmap bitmap = null;
            url = strings[0];

            try {
                bitmap =  mCache.getBitmapFromURL(url, reqWidth, reqHeight);
            } catch (IOException e) {
            }

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (isCancelled()) return;

            if (imageViewReference != null) {
                ImageView imageView = imageViewReference.get();
                //BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);

                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    AsyncTag tag = (AsyncTag) imageView.getTag();
                    if (tag != null) tag.setUrl(url);
                }
            }
        }
    }

    private static class AsyncTag {
        private WeakReference<BitmapDownloaderTask> bitmapDownloaderTaskReference;
        private String url;

        public AsyncTag(BitmapDownloaderTask bitmapDownloaderTask) {
            bitmapDownloaderTaskReference = new WeakReference<BitmapDownloaderTask>(bitmapDownloaderTask);
        }

        public BitmapDownloaderTask getBitmapDownloaderTask() {
            return bitmapDownloaderTaskReference.get();
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    private static BitmapDownloaderTask getBitmapDownloaderTask(ImageView imageView) {
        if (imageView != null) {
            AsyncTag tag = (AsyncTag) imageView.getTag();
            if (tag != null) return tag.getBitmapDownloaderTask();
        }
        return null;
    }

    private static boolean cancelPotentialDownload(String url, ImageView imageView) {
        if (imageView != null) {
            AsyncTag tag = (AsyncTag) imageView.getTag();
            if (tag != null && tag.getUrl() != null && tag.getUrl().equals(url)) {
                return false;
            }
        }

        BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);

        if (bitmapDownloaderTask != null) {
            String bitmapUrl = bitmapDownloaderTask.url;
            if ((bitmapUrl == null) || (!bitmapUrl.equals(url))) {
                bitmapDownloaderTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }
}
