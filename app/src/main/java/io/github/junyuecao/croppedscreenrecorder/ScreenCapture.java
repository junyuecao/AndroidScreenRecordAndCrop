package io.github.junyuecao.croppedscreenrecorder;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import android.app.Activity;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.os.Build;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Screen capture
 * process：
 * 1，request for capture permission，
 * 2，start projection，
 * 3，attach encoder，
 * 4，detach encoder when finish，
 * 5，close projection and destroy
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ScreenCapture {
    private static final String TAG = "ScreenCapture";
    public static final int CAPTURE_REQUEST_CODE = 110;
    private static ScreenCapture sInstance;
    private final WeakReference<Activity> mActivity;
    private final int mScreenDensity;
    private MediaProjectionManager projectionManager;
    private TextureMovieEncoder mRecorder;
    private int width;
    private int height;
    private VirtualDisplay virtualDisplay;
    private boolean running;
    private MediaProjection mediaProjection;
    private OnMediaProjectionReadyListener mMediaProjectionReadyListener;
    private boolean recording;
    private int mBitRate = 5 * 1024 * 1024;

    private ScreenCapture(Activity activity) {
        mActivity = new WeakReference<>(activity);
        projectionManager = (MediaProjectionManager) activity.getSystemService(MEDIA_PROJECTION_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        width = 720;
        height = 1280;
        mRecorder = new TextureMovieEncoder();
    }

    public static ScreenCapture getInstance(Activity activity) {
        if (sInstance == null) {
            synchronized(ScreenCapture.class) {
                if (sInstance == null) {
                    sInstance = new ScreenCapture(activity);
                }
            }
        }
        return sInstance;
    }

    /**
     * @return true when projecting
     */
    public boolean isProjecting() {
        return running;
    }

    /**
     * @return retrun true when recording screen
     */
    public boolean isRecording() {
        return recording;
    }

    public OnMediaProjectionReadyListener getMediaProjectionReadyListener() {
        return mMediaProjectionReadyListener;
    }

    public void setMediaProjectionReadyListener(OnMediaProjectionReadyListener mediaProjectionReadyListener) {
        this.mMediaProjectionReadyListener = mediaProjectionReadyListener;
    }

    /**
     * Step 1: request permission
     */
    public void requestScreenCapture() {
        Log.d(TAG, "Start requestScreenCapture");
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        mActivity.get().startActivityForResult(captureIntent, CAPTURE_REQUEST_CODE);
    }

    /**
     * Step 2，Init MediaProjection
     *
     * @param data data returned from onActivityResult
     *
     * @return true if success
     */
    public boolean startProjection(Intent data) {
        Log.d(TAG, "Start startProjection");
        mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, data);
        if (mediaProjection == null) {
            return false;
        }

        if (mMediaProjectionReadyListener != null) {
            mMediaProjectionReadyListener.onMediaProjectionReady(mediaProjection);
        }
        createVirtualDisplay();
        running = true;
        return true;
    }

    /**
     * Step 3，attach encoder to the virtual screen and start to record
     *
     * @return true if attach success
     */
    public boolean attachRecorder() {
        Log.d(TAG, "Start attachRecorder");
        if (!running || recording) {
            // if not projecting screen or already recording return false
            return false;
        }
        EGLContext eglContext = EGL14.eglGetCurrentContext();
        File file = getFile();
        float cropTop = ((float) Utils.getStatusBarHeight(mActivity.get())) /  Utils.getRealHeight(mActivity.get());
        float cropBottom = ((float)Utils.getNavBarHeight(mActivity.get())) / Utils.getRealHeight(mActivity.get());
        mRecorder.startRecording(new TextureMovieEncoder.EncoderConfig(file,
                width, height,
                cropTop, cropBottom, mBitRate, eglContext));
        mRecorder.setCallback(new TextureMovieEncoder.Callback() {
            @Override
            public void onEncoderPrepared(Surface surface) {
                virtualDisplay.setSurface(surface);
            }
        });

        recording = true;
        return true;
    }

    /**
     * Step 4，detach encoder from virtual screen and stop recoding.
     *
     * @return true if success
     */
    public boolean detachRecorder() {
        Log.d(TAG, "Start detachRecorder");
        if (!running || !recording) {
            // if not projecting or not recording return false
            return false;
        }
        recording = false;

        mRecorder.stopRecording();
        virtualDisplay.setSurface(null);

        return true;
    }

    /**
     * Step 5：stop projection and destroy
     */
    public boolean stopProjection() {
        Log.d(TAG, "Start stopProjection");
        if (!running) {
            return false;
        }
        running = false;
        if (recording) {
            detachRecorder();
        }
        virtualDisplay.release();
        mediaProjection.stop();

        return true;
    }


    private boolean isCurrentActivity(Activity activity) {
        return mActivity.get() == activity;
    }

    private void createVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "LiveScreen",
                width,
                height,
                mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, // we don't need to display by now
                new VirtualDisplay.Callback() {
                    @Override
                    public void onPaused() {
                        super.onPaused();
                        Log.d(TAG, "onPaused: VirtualDisplay");
                    }

                    @Override
                    public void onResumed() {
                        super.onResumed();
                        Log.d(TAG, "onResumed: VirtualDisplay");
                    }
                }, null);
    }

    @NonNull
    private File getFile() {
        File file = new File(Environment.getExternalStorageDirectory() + File.separator + "test", System.currentTimeMillis() + ".mp4");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    public interface OnMediaProjectionReadyListener {
        void onMediaProjectionReady(MediaProjection mediaProjection);
    }
}
