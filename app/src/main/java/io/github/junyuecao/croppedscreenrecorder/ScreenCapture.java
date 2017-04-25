package io.github.junyuecao.croppedscreenrecorder;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;
import static io.github.junyuecao.croppedscreenrecorder.VideoEncoderCore.DEFAULT_CHANNEL_CONFIG;
import static io.github.junyuecao.croppedscreenrecorder.VideoEncoderCore.DEFAULT_DATA_FORMAT;
import static io.github.junyuecao.croppedscreenrecorder.VideoEncoderCore.DEFAULT_SAMPLE_RATE;
import static io.github.junyuecao.croppedscreenrecorder.VideoEncoderCore.DEFAULT_SOURCE;

import android.app.Activity;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioRecord;
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
import java.nio.ByteBuffer;

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
    private static final int SAMPLES_PER_FRAME = 2048;
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

    private boolean mAudioLoopExited = false;
    private Thread mAudioThread;
    private AudioRecord mAudioRecord;

    public ScreenCapture(Activity activity) {
        mActivity = new WeakReference<>(activity);
        projectionManager = (MediaProjectionManager) activity.getSystemService(MEDIA_PROJECTION_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        width = 720;
        height = 1280;
        mRecorder = new TextureMovieEncoder();
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
            public void onInputSurfacePrepared(Surface surface) {
                virtualDisplay.setSurface(surface);
            }
        });

        // init AudioRecord to record from mic
        initAudioRecord(DEFAULT_SOURCE, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_CONFIG, DEFAULT_DATA_FORMAT);

        mAudioLoopExited = false;
        mAudioThread = new Thread(new AudioRunnable());
        mAudioThread.start();

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
        mAudioLoopExited = true;

        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }

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
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    public interface OnMediaProjectionReadyListener {
        void onMediaProjectionReady(MediaProjection mediaProjection);
    }

    private boolean initAudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat) {
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid parameter !");
            return false;
        }
        mAudioRecord = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, minBufferSize * 4);
        if (mAudioRecord.getState() == AudioRecord.STATE_UNINITIALIZED) {
            Log.e(TAG, "AudioRecord initialize fail !");
            return false;
        }

        mAudioRecord.startRecording();
        return true;
    }

    private class AudioRunnable implements Runnable {
        private byte[] mBuffer;

        @Override
        public void run() {
            while (!mAudioLoopExited) {
                enqueueAudioFrame(false);
            }

            enqueueAudioFrame(true);
        }

        private void enqueueAudioFrame(boolean endOfStream) {
            if (mBuffer == null) {
                mBuffer = new byte[SAMPLES_PER_FRAME * 2]; // prevent recreate buffer
            }
            int ret = mAudioRecord.read(mBuffer, 0, mBuffer.length);
            if (ret == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "Error ERROR_INVALID_OPERATION");
            } else if (ret == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Error ERROR_BAD_VALUE");
            } else {
                ByteBuffer buf = ByteBuffer.wrap(mBuffer);
                if (endOfStream) {
                    Log.d(TAG, "AudioLoopExiting, add flag end of stream");
                    mRecorder.audioFrameAvailable(buf, ret, true);
                } else {
                    mRecorder.audioFrameAvailable(buf, ret, false);
                }
            }
        }
    }
}
