package io.github.junyuecao.croppedscreenrecorder;

/**
 * Record callback
 */
public interface RecordCallback {

    /**
     * Callback when record successfully
     * @param filePath recorded MP4 file path
     */
    void onRecordSuccess(String filePath, String coverPath, long duration);

    /**
     * Callback when record failed
     * @param e reason why it failed
     */
    void onRecordFailed(Throwable e, long duration);

    /**
     * Record progress changed
     * @param ms current record duration in ms
     */
    void onRecordedDurationChanged(long ms);
}
