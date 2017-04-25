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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
 * 2，start projection， (running)
 * 3，attach encoder， (recording)
 * 4，detach encoder when finish，
 * 5，close projection and destroy
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ScreenCapture {
    public static final int MSG_SEND_AUDIO_FRAME = 0;
    public static final int MSG_STOP = 1;
    public static final int MSG_QUIT = 2;

    private static final String TAG = "ScreenCapture";
    public static final int CAPTURE_REQUEST_CODE = 8080;
    private static final int SAMPLES_PER_FRAME = 2048;
    private final WeakReference<Activity> mActivity; // Prevent memory leak
    private final int mScreenDensity;
    private MediaProjectionManager projectionManager;
    private TextureMovieEncoder mRecorder;
    private int width = 360; // Width of the recorded video
    private int height = 640; // Height of the recorded video
    private int mBitRate = 1 * 1024 * 1024;

    private boolean running; // true if it is projecting screen
    private boolean recording; // true if it is recording screen

    private VirtualDisplay virtualDisplay;
    private MediaProjection mediaProjection;
    private OnMediaProjectionReadyListener mMediaProjectionReadyListener;

    private AudioFrameSender mAudioSender;
    private boolean mAudioLoopExited;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;

    public ScreenCapture(Activity activity) {
        mActivity = new WeakReference<>(activity);
        projectionManager = (MediaProjectionManager) activity.getSystemService(MEDIA_PROJECTION_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        mRecorder = new TextureMovieEncoder();
    }

    public RecordCallback getRecordCallback() {
        return mRecorder.getRecordCallback();
    }

    public void setRecordCallback(RecordCallback recordCallback) {
        mRecorder.setRecordCallback(recordCallback);
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
        if (!running) {
            // if not projecting screen or already recording return false
            requestScreenCapture();
            return false;
        }
        if (recording) {
            return false;
        }
        EGLContext eglContext = EGL14.eglGetCurrentContext();
        File file = getFile();
        float cropTop = ((float) Utils.getStatusBarHeight(mActivity.get())) / Utils.getRealHeight(mActivity.get());
        float cropBottom = ((float) Utils.getNavBarHeight(mActivity.get())) / Utils.getRealHeight(mActivity.get());
        mRecorder.startRecording(new TextureMovieEncoder.EncoderConfig(file,
                width, height,
                cropTop, cropBottom, mBitRate, eglContext));
        mRecorder.setCallback(new TextureMovieEncoder.Callback() {
            @Override
            public void onInputSurfacePrepared(Surface surface) {
                virtualDisplay.setSurface(surface);
            }
        });

        mAudioSender = new AudioFrameSender();
        mAudioSender.start();

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

        if (mAudioSender != null) {
            mAudioSender.stop();
            mAudioSender = null;
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
        if (recording) {
            detachRecorder();
        }
        running = false;
        virtualDisplay.release();
        mediaProjection.stop();

        return true;
    }

    public void sendAudioFrame(ByteBuffer byteBuffer, int size, boolean isEnd) {
        if (mAudioSender != null && mAudioSender.isRunning()) {
            mAudioSender.sendAudioFrame(byteBuffer, size, isEnd);
        }
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
        File file = new File(
                Environment.getExternalStorageDirectory() + File.separator + "test",
                System.currentTimeMillis() + ".mp4");
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    public interface OnMediaProjectionReadyListener {
        void onMediaProjectionReady(MediaProjection mediaProjection);
    }

    /**
     * 用户发送音频帧数据
     */
    private class AudioFrameSender implements Runnable {
        // ----- accessed by multiple threads -----
        private volatile AudioFrameHandler mHandler;
        private final Object mReadyFence = new Object();      // guards ready/running
        private boolean mReady;
        private boolean mRunning;

        @Override
        public void run() {
            // Establish a Looper for this thread, and define a Handler for it.
            Looper.prepare();
            synchronized(mReadyFence) {
                mHandler = new AudioFrameHandler(this);
                mReady = true;
                mReadyFence.notify();
            }
            Looper.loop();

            Log.d(TAG, "AudioFrameSender thread exiting");
            synchronized(mReadyFence) {
                mReady = mRunning = false;
                mHandler = null;
            }
        }

        /**
         * 这个Sender必须先start才能发送音频帧
         */
        public void start() {
            Log.d(TAG, "AudioFrameSender: startRecording()");
            synchronized(mReadyFence) {
                if (mRunning) {
                    Log.w(TAG, "AudioFrameSender thread already running");
                    return;
                }
                mRunning = true;
                new Thread(this, "AudioFrameSender").start();
                while (!mReady) {
                    try {
                        mReadyFence.wait();
                    } catch (InterruptedException ie) {
                        // ignore
                    }
                }
            }
        }

        public boolean isRunning() {
            return mRunning;
        }

        public void stop() {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP));
            mHandler.sendMessage(mHandler.obtainMessage(MSG_QUIT));
        }

        /**
         * 发送AudioFrame到编码器
         */
        public void sendAudioFrame(ByteBuffer buffer, int size, boolean endOfStream) {
            synchronized(mReadyFence) {
                if (!mReady) {
                    return;
                }
            }

            mHandler.sendMessage(mHandler.obtainMessage(MSG_SEND_AUDIO_FRAME, size, endOfStream ? 1 : 0, buffer));
        }

        private void handleSendAudioFrame(ByteBuffer buffer, int size, boolean endOfStream) {
            if (mRecorder.isRecording()) {
                mRecorder.audioFrameAvailable(buffer, size, endOfStream);
            }
        }
    }

    /**
     * Handles encoder state change requests.  The handler is created on the encoder thread.
     */
    private static class AudioFrameHandler extends Handler {
        private WeakReference<AudioFrameSender> mWeakSender;

        AudioFrameHandler(AudioFrameSender sender) {
            mWeakSender = new WeakReference<>(sender);
        }

        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
            Object obj = msg.obj;

            AudioFrameSender frameSender = mWeakSender.get();
            if (frameSender == null) {
                Log.w(TAG, "AudioFrameSender.handleMessage: frameSender is null");
                return;
            }
            switch (what) {
                case MSG_SEND_AUDIO_FRAME:
                    frameSender.handleSendAudioFrame((ByteBuffer) obj, msg.arg1, msg.arg2 == 1);
                    break;
                case MSG_STOP:
                    Log.d(TAG, "send end of audio stream message");
                    frameSender.handleSendAudioFrame((ByteBuffer) obj, msg.arg1, true);
                    break;
                case MSG_QUIT:
                    Log.d(TAG, "Exit sender loop");
                    Looper.myLooper().quit();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }

        }
    }



    // Audio test

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
                sendAudioFrame(buf, ret, endOfStream);
                Log.d(TAG, "AudioLoopExiting, add flag end of stream");
            }
        }
    }
}
