package io.github.junyuecao.croppedscreenrecorder;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import android.app.Activity;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
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
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * 抓取屏幕
 * 流程：
 * 1，请求权限，
 * 2，开始投射，
 * 3，附着MediaRecorder录像，
 * 4，录完断开MediaRecorder，
 * 5，关闭投射，销毁
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ScreenCapture {
    private static final String TAG = "ScreenCapture";
    public static final int CAPTURE_REQUEST_CODE = 110;
    private static ScreenCapture sInstance;
    private final WeakReference<Activity> mActivity;
    private final int mScreenDensity;
    private MediaProjectionManager projectionManager;
    private MediaRecorder mediaRecorder;
    private TextureMovieEncoder mRecorder;
    private int width;
    private int height;
    private VirtualDisplay virtualDisplay;
    private boolean running;
    private MediaProjection mediaProjection;
    private OnMediaProjectionReadyListener mMediaProjectionReadyListener;
    private boolean recording;
    private int mBitRate = 5 * 1024 * 1024;
    private int mFrameRate = 30;

    private ScreenCapture(Activity activity) {
        mActivity = new WeakReference<>(activity);
        projectionManager = (MediaProjectionManager) activity.getSystemService(MEDIA_PROJECTION_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        width = 720;
        height = 1280;
        mediaRecorder = new MediaRecorder();
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
     * @return 是否正在投屏
     */
    public boolean isProjecting() {
        return running;
    }

    /**
     * @return 是否正在录屏
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
     * 第一步：请求屏幕捕获
     */
    public void requestScreenCapture() {
        Log.d(TAG, "Start requestScreenCapture");
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        mActivity.get().startActivityForResult(captureIntent, CAPTURE_REQUEST_CODE);
    }

    /**
     * 第二步，初始化MediaProjection
     *
     * @param data onActivityResult返回的data
     *
     * @return 是否成功
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
     * 第3步，将Encoder附着到虚拟屏幕上，开始录屏
     *
     * @return 成功或失败
     */
    public boolean attachRecorder() {
        Log.d(TAG, "Start attachRecorder");
        if (!running || recording) {
            // 没有投屏或者正在录屏，都返回失败
            return false;
        }
        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
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

        // initRecorder();
        // final Surface surface = mediaRecorder.getSurface();
        // virtualDisplay.setSurface(surface);
        // mediaRecorder.start();
        recording = true;
        return true;
    }

    /**
     * 第4步，将MediaRecorder附着到虚拟屏幕上，开始录屏
     *
     * @return 成功或失败
     */
    public boolean detachRecorder() {
        Log.d(TAG, "Start detachRecorder");
        if (!running || !recording) {
            // 没有投屏，或者没有在录屏，返回失败
            return false;
        }
        recording = false;

        mRecorder.stopRecording();
        virtualDisplay.setSurface(null);


        // mediaRecorder.stop();
        // virtualDisplay.setSurface(null);
        // mediaRecorder.reset();

        return true;
    }

    /**
     * 第5步：结束投屏，完毕后调用
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
                null, // 先不需要显示
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

    private void initRecorder() {
        File file = getFile();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mediaRecorder.setOutputFile(file.getAbsolutePath());
        mediaRecorder.setVideoSize(width, height);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mediaRecorder.setVideoEncodingBitRate(mBitRate);
        mediaRecorder.setVideoFrameRate(mFrameRate);
        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @NonNull
    private File getFile() {
        return new File(Environment.getExternalStorageDirectory() + File.separator + "test", System.currentTimeMillis() + ".mp4");
    }

    public interface OnMediaProjectionReadyListener {
        void onMediaProjectionReady(MediaProjection mediaProjection);
    }
}
