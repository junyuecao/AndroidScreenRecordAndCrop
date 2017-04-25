package io.github.junyuecao.croppedscreenrecorder;

/**
 * 录制回调
 */
public interface RecordCallback {

    /**
     * 录制成功的回调
     * @param filePath 录制后的文件路径
     */
    void onRecordSuccess(String filePath, String coverPath, long duration);

    /**
     * 录制失败后的回调
     * @param e 录制失败的原因
     */
    void onRecordFailed(Throwable e, long duration);

    /**
     * 本次录制时长变化 注意：不在主线程执行
     * @param ms 当前视频长度 毫秒
     */
    void onRecordedDurationChanged(long ms);
}
