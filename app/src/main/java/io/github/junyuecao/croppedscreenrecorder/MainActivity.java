package io.github.junyuecao.croppedscreenrecorder;

import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.view.MotionEventCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnNeverAskAgain;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.RuntimePermissions;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

@RuntimePermissions
public class MainActivity extends AppCompatActivity implements View.OnClickListener, View.OnTouchListener,
        RecordCallback {

    ScreenCapture mScreenCapture;
    Timer mTimer;
    TextView mTime;
    Handler mHandler;
    private Button mStart;
    private LinearLayout mRecordLayout;
    private Button mRecord;
    private Button mCancel;

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.start:
                MainActivityPermissionsDispatcher.tryRecordScreenWithCheck(this);
                break;
            case R.id.cancel:
                mRecordLayout.setVisibility(View.GONE);
                mStart.setVisibility(View.VISIBLE);
                mScreenCapture.stopProjection();
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // NOTE: delegate the permission handling to generated method
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v.getId() == R.id.record) {
            // Touch and hold to record, release to stop record
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                switch (MotionEventCompat.getActionMasked(event)) {
                    case MotionEvent.ACTION_DOWN:
                        mScreenCapture.attachRecorder();
                        return true;
                    case MotionEvent.ACTION_UP:
                        mScreenCapture.detachRecorder();
                        return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onRecordSuccess(String filePath, String coverPath, long duration) {
        Toast.makeText(this, "Record successfully: " + filePath, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordFailed(Throwable e, long duration) {
        Toast.makeText(this, "Record failed with error : " + e.getMessage(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordedDurationChanged(long ms) {
        // We don't need it yet
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mHandler = new Handler();
        mTime = (TextView) findViewById(R.id.time);

        mStart = (Button) findViewById(R.id.start);
        mRecordLayout = (LinearLayout) findViewById(R.id.recordLayout);
        mRecord = (Button) findViewById(R.id.record);
        mCancel = (Button) findViewById(R.id.cancel);

        mStart.setOnClickListener(this);
        mCancel.setOnClickListener(this);
        mRecord.setOnTouchListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mTime.setText("" + System.currentTimeMillis() + "\n" + (new Date().toString()));
                    }
                });
            }
        }, 0, 100);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTimer.cancel();
        mTimer = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mScreenCapture != null && mScreenCapture.isProjecting()) {
            mScreenCapture.stopProjection();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void tryRecordScreen() {
        if (mScreenCapture == null) {
            mScreenCapture = new ScreenCapture(this);
        }
        mScreenCapture.setMediaProjectionReadyListener(new ScreenCapture.OnMediaProjectionReadyListener() {
            @Override
            public void onMediaProjectionReady(MediaProjection mediaProjection) {
                mRecordLayout.setVisibility(View.VISIBLE);
                mStart.setVisibility(View.GONE);
            }
        });
        mScreenCapture.setRecordCallback(this);
        mScreenCapture.requestScreenCapture();
    }

    @OnPermissionDenied(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showDenied() {
        Toast.makeText(this, "Need permission to work properly", Toast.LENGTH_SHORT).show();
    }

    @OnNeverAskAgain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showNeverAsk() {
        Toast.makeText(this, "Need permission to work properly", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ScreenCapture.CAPTURE_REQUEST_CODE && resultCode == RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mScreenCapture.startProjection(data);
            }
            return;
        }
    }

}
