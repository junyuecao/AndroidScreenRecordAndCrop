package io.github.junyuecao.croppedscreenrecorder;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * 录屏器
 */
@RequiresApi(LOLLIPOP)
public class ScreenRecorder implements Runnable {
    private static final String TAG = "ScreenRecorder";
    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_FRAME_AVAILABLE = 2;
    private static final int MSG_SET_TEXTURE_ID = 3;
    private static final int MSG_UPDATE_SHARED_CONTEXT = 4;
    private static final int MSG_QUIT = 5;
    private static final long FRAME_INTERVAL = 16;
    private static final boolean VERBOSE = true;

    private VideoEncoderCore mVideoEncoder;


    // ----- accessed by multiple threads -----
    private volatile EncoderHandler mHandler;


    private Object mReadyFence = new Object();      // guards ready/running
    private boolean mReady;
    private boolean mRunning;
    private int mFrameNum;
    private Surface mSurface;
    private VirtualDisplay mDisplay;

    @Override
    public void run() {
        // Establish a Looper for this thread, and define a Handler for it.
        Looper.prepare();
        synchronized (mReadyFence) {
            mHandler = new EncoderHandler(this);
            mReady = true;
            mReadyFence.notify();
        }
        Looper.loop();

        Log.d(TAG, "Encoder thread exiting");
        synchronized (mReadyFence) {
            mReady = mRunning = false;
            mHandler = null;
        }
    }
    /**
     * Tells the video recorder to start recording.  (Call from non-encoder thread.)
     * <p>
     * Creates a new thread, which will create an encoder using the provided configuration.
     * <p>
     * Returns after the recorder thread has started and is ready to accept Messages.  The
     * encoder may not yet be fully configured.
     */
    public void startRecording(EncoderConfig config) {
        Log.d(TAG, "Encoder: startRecording()");
        synchronized (mReadyFence) {
            if (mRunning) {
                Log.w(TAG, "Encoder thread already running");
                return;
            }
            mRunning = true;
            new Thread(this, "TextureMovieEncoder").start();
            while (!mReady) {
                try {
                    mReadyFence.wait();
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_RECORDING, config));
    }

    /**
     * Tells the video recorder to stop recording.  (Call from non-encoder thread.)
     * <p>
     * Returns immediately; the encoder/muxer may not yet be finished creating the movie.
     * <p>
     * TODO: have the encoder thread invoke a callback on the UI thread just before it shuts down
     * so we can provide reasonable status UI (and let the caller know that movie encoding
     * has completed).
     */
    public void stopRecording() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
        mHandler.sendMessage(mHandler.obtainMessage(MSG_QUIT));
        // We don't know when these will actually finish (or even start).  We don't want to
        // delay the UI thread though, so we return immediately.
    }

    /**
     * Returns true if recording has been started.
     */
    public boolean isRecording() {
        synchronized (mReadyFence) {
            return mRunning;
        }
    }

    public void attachToDisplay(VirtualDisplay virtualDisplay) {
        virtualDisplay.setSurface(mSurface);
    }

    /**
     * 通信通道
     */
    private static class EncoderHandler extends Handler {
        private WeakReference<ScreenRecorder> mWeakRecorder;
        EncoderHandler(ScreenRecorder recorder) {
            mWeakRecorder = new WeakReference<>(recorder);
        }

        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
            Object obj = msg.obj;

            ScreenRecorder encoder = mWeakRecorder.get();
            if (encoder == null) {
                Log.w(TAG, "ScreenRecorder.handleMessage: encoder is null");
                return;
            }

            switch (what) {
                case MSG_START_RECORDING:
                    encoder.handleStartRecording((EncoderConfig) obj);
                    break;
                case MSG_STOP_RECORDING:
                    encoder.handleStopRecording();
                    break;
                case MSG_FRAME_AVAILABLE:
                    encoder.handleFrameAvailable();
                    sendEmptyMessageDelayed(MSG_FRAME_AVAILABLE, FRAME_INTERVAL);
                    break;
                // case MSG_SET_TEXTURE_ID:
                //     encoder.handleSetTexture(inputMessage.arg1);
                //     break;
                // case MSG_UPDATE_SHARED_CONTEXT:
                //     encoder.handleUpdateSharedContext((EGLContext) inputMessage.obj);
                //     break;
                case MSG_QUIT:
                    Looper.myLooper().quit();
                    break;
                default:
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }
    /**
     * Handles notification of an available frame.
     */
    private void handleFrameAvailable() {
        if (VERBOSE) Log.d(TAG, "handleFrameAvailable");
        mVideoEncoder.drainEncoder(false);
    }

    /**
     * Handles a request to stop encoding.
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording");
        mHandler.removeMessages(MSG_FRAME_AVAILABLE);
        mVideoEncoder.drainEncoder(true);
        releaseEncoder();
    }

    /**
     * Starts recording.
     */
    private void handleStartRecording(EncoderConfig config) {
        Log.d(TAG, "handleStartRecording " + config);
        mFrameNum = 0;
        prepareEncoder(config.mWidth, config.mHeight, config.mBitRate, config.mOutputFile, config.mDisplay);
    }

    private void prepareEncoder(int width, int height, int bitRate, File outputFile, VirtualDisplay display) {
        try {
            mVideoEncoder = new VideoEncoderCore(width, height, bitRate, outputFile);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mSurface = mVideoEncoder.getInputSurface();
        mDisplay = display;
        mDisplay.setSurface(mSurface);

        mHandler.sendEmptyMessageDelayed(MSG_FRAME_AVAILABLE, FRAME_INTERVAL);
    }

    private void releaseEncoder() {
        mVideoEncoder.release();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mDisplay != null) {
            mDisplay.setSurface(null);
            mDisplay = null;
        }
    }

    /**
     * Encoder configuration.
     * <p>
     * Object is immutable, which means we can safely pass it between threads without
     * explicit synchronization (and don't need to worry about it getting tweaked out from
     * under us).
     * <p>
     * TODO: make frame rate and iframe interval configurable?  Maybe use builder pattern
     *       with reasonable defaults for those and bit rate.
     */
    public static class EncoderConfig {
        final File mOutputFile;
        final int mWidth;
        final int mHeight;
        final int mBitRate;
        final VirtualDisplay mDisplay;

        public EncoderConfig(File outputFile, int width, int height, int bitRate, VirtualDisplay display) {
            mOutputFile = outputFile;
            mWidth = width;
            mHeight = height;
            mBitRate = bitRate;
            mDisplay = display;
        }

        @Override
        public String toString() {
            return "EncoderConfig: " + mWidth + "x" + mHeight + " @" + mBitRate +
                    " to '" + mOutputFile.toString();
        }
    }
}
