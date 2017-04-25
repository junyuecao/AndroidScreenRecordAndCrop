/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.junyuecao.croppedscreenrecorder;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
@RequiresApi(LOLLIPOP)
public class VideoEncoderCore {
    private static final String TAG = "VideoEncoderCore";
    private static final boolean VERBOSE = true;
    private static final int TIMEOUT_USEC = 10000;
    public static final int DEFAULT_SOURCE = MediaRecorder.AudioSource.MIC;
    public static final int DEFAULT_SAMPLE_RATE = 44100;
    public static final int DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_DEFAULT;
    public static final int DEFAULT_DATA_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public static final int MAX_INPUT_SIZE = 2048;
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;    // H.264 Advanced Video Coding
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    /**
     * fps
     */
    private static final int FRAME_RATE = 24;
    /**
     * 5 seconds between I-frames
     */
    private static final int IFRAME_INTERVAL = 5;
    /**
     * Save path
     */
    private final String mPath;

    private Surface mInputSurface;
    private MediaMuxer mMuxer;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mVBufferInfo;
    private MediaCodec.BufferInfo mABufferInfo;
    private int mVTrackIndex;
    private int mATrackIndex;
    private boolean mMuxerStarted;
    private boolean mStreamEnded;
    private long mRecordStartedAt = 0;
    private long mRecordDuration = 0;

    private RecordCallback mCallback;
    private Handler mMainHandler;

    private Runnable mRecordProgressChangeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCallback != null) {
                mCallback.onRecordedDurationChanged(mRecordDuration);
            }
        }
    };
    private String mCoverPath;

    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public VideoEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {
        mMainHandler = new Handler(Looper.getMainLooper());
        mVBufferInfo = new MediaCodec.BufferInfo();
        mABufferInfo = new MediaCodec.BufferInfo();

        MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) {
            Log.d(TAG, "videoFormat: " + videoFormat);
        }

        // Create a MediaCodec encoder, and configure it with our videoFormat.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mVideoEncoder.createInputSurface();
        mVideoEncoder.start();

        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE,
                DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_CONFIG);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
        mStreamEnded = false;

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mPath = outputFile.toString();
        mMuxer = new MediaMuxer(mPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mVTrackIndex = -1;
        mATrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * Releases encoder resources.
     */
    public void release() {
        if (VERBOSE) {
            Log.d(TAG, "releasing encoder objects");
        }

        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
        if (mMuxer != null) {
            try {
                mMuxer.stop();
                if (mCallback != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCallback.onRecordSuccess(mPath, mCoverPath, mRecordDuration);
                        }
                    });
                }
            } catch (final IllegalStateException e) {
                Log.w(TAG, "Record failed with error:", e);
                if (mCallback != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCallback.onRecordFailed(e, mRecordDuration);
                        }
                    });
                }
            }
            try {
                mMuxer.release();
            } catch (IllegalStateException ex) {
                Log.w(TAG, "Record failed with error:", ex);
            }

            mMuxer = null;
        }

    }

    public String getCoverPath() {
        return mCoverPath;
    }

    public void setCoverPath(String coverPath) {
        mCoverPath = coverPath;
    }

    public RecordCallback getRecordCallback() {
        return mCallback;
    }

    public void setRecordCallback(RecordCallback callback) {
        mCallback = callback;
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    public void drainEncoder(boolean endOfStream) {
        if (VERBOSE) {
            Log.d(TAG, "drainEncoder(" + endOfStream + ")");
        }

        if (endOfStream) {
            if (VERBOSE) {
                Log.d(TAG, "sending EOS to encoder");
            }
            mVideoEncoder.signalEndOfInputStream();
            mStreamEnded = true;
        }

        drainVideo(endOfStream);
        drainAudio(endOfStream);

        if (mMuxerStarted && mCallback != null) {
            mRecordDuration = System.currentTimeMillis() - mRecordStartedAt;
            mMainHandler.post(mRecordProgressChangeRunnable);
        }
    }

    private void drainVideo(boolean endOfStream) {
        while (true) {
            int encoderStatus = mVideoEncoder.dequeueOutputBuffer(mVBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (mStreamEnded) {
                        break;
                    }
                    if (VERBOSE) {
                        Log.d(TAG, "no video output available, spinning to await EOS");
                    }
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                Log.d(TAG, "video encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mVTrackIndex = mMuxer.addTrack(newFormat);
                tryStartMuxer();
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                if (mMuxerStarted) {
                    // same as mVideoEncoder.getOutputBuffer(encoderStatus)
                    ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(encoderStatus);

                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    if ((mVBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        if (VERBOSE) {
                            Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        }
                        mVBufferInfo.size = 0;
                    }

                    if (mVBufferInfo.size != 0) {
                        if (!mMuxerStarted) {
                            throw new RuntimeException("muxer hasn't started");
                        }

                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(mVBufferInfo.offset);
                        encodedData.limit(mVBufferInfo.offset + mVBufferInfo.size);

                        mMuxer.writeSampleData(mVTrackIndex, encodedData, mVBufferInfo);
                        if (VERBOSE) {
                            Log.d(TAG, "sent " + mVBufferInfo.size + " video bytes to muxer, ts=" +
                                    mVBufferInfo.presentationTimeUs);
                        }
                    }

                    mVideoEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((mVBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!endOfStream) {
                            Log.w(TAG, "reached end of stream unexpectedly");
                        } else {
                            if (VERBOSE) {
                                Log.d(TAG, "end of video stream reached");
                            }
                        }
                        break;      // out of while
                    }
                } else {
                    Log.w(TAG, "Muxer is not started, just return");
                    // let's ignore it
                    mVideoEncoder.releaseOutputBuffer(encoderStatus, false);
                }
            }
        }
    }

    public void drainAudio(boolean endOfStream) {
        while (true) {
            // Start to get data from OutputBuffer and write to Muxer
            int index = mAudioEncoder.dequeueOutputBuffer(mABufferInfo, TIMEOUT_USEC);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (mStreamEnded) {
                        break;
                    }
                    if (VERBOSE) {
                        Log.d(TAG, "no audio output available, spinning to await EOS");
                    }
                }
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mATrackIndex != -1) {
                    throw new RuntimeException("format changed twice");
                }
                mATrackIndex = mMuxer.addTrack(mAudioEncoder.getOutputFormat());
                tryStartMuxer();
            } else if (index >= 0) {
                if (mMuxerStarted) {
                    if ((mABufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // ignore codec config
                        mABufferInfo.size = 0;
                    }

                    if (mABufferInfo.size != 0) {
                        ByteBuffer out = mAudioEncoder.getOutputBuffer(index);
                        out.position(mABufferInfo.offset);
                        out.limit(mABufferInfo.offset + mABufferInfo.size);
                        mMuxer.writeSampleData(mATrackIndex, out, mABufferInfo);

                        if (VERBOSE) {
                            Log.d(TAG, "sent " + mABufferInfo.size + " audio bytes to muxer, ts=" +
                                    mABufferInfo.presentationTimeUs);
                        }
                    }

                    mAudioEncoder.releaseOutputBuffer(index, false);

                    if ((mABufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!endOfStream) {
                            Log.w(TAG, "reached end of stream unexpectedly");
                        } else {
                            if (VERBOSE) {
                                Log.d(TAG, "end of audio stream reached");
                            }
                        }
                        mStreamEnded = true; // Audio stream ended
                        break;      // out of while
                    }
                } else {
                    Log.w(TAG, "Muxer is not started, just return");
                    // let's ignore it
                    mAudioEncoder.releaseOutputBuffer(index, false); // Don't forget to release it
                }
            }
        }
    }

    /**
     * Enqueue the audio frame buffers to the encoder, this should not be done in the same thread with encoder.
     *
     * @param buffer      the data
     * @param size        size of the data
     * @param endOfStream is this frame the end
     */
    public void enqueueAudioFrame(ByteBuffer buffer, int size, boolean endOfStream) {
        boolean done = false;
        while (!done) {
            // Start to put data to InputBuffer
            int index = mAudioEncoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (index >= 0) { // In case we didn't get any input buffer, it may be blocked by all output buffers being
                // full, thus try to drain them below if we didn't get any
                ByteBuffer in = mAudioEncoder.getInputBuffer(index);
                in.clear();
                if (size < 0) {
                    size = 0;
                }
                if (buffer == null) {
                    buffer = ByteBuffer.allocate(0);
                    size = 0;
                }
                in.position(0);
                in.limit(size);
                buffer.position(0);
                buffer.limit(size);
                if (VERBOSE) {
                    Log.d(TAG, "enqueueAudioFrame: "
                            + "buffer [pos:" + buffer.position() + ", limit: " + buffer.limit() + "]"
                            + "in [pos:" + in.position() + ", capacity: " + in.capacity() + "]");
                }
                in.put(buffer); // Here we should ensure that `size` is smaller than the capacity of the `in` buffer
                int flag = endOfStream ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                mAudioEncoder.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, flag);
                done = true; // Done passing the input to the codec, but still check for available output below
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // if input buffers are full try to drain them
                if (VERBOSE) {
                    Log.d(TAG, "no input available, spinning to await EOS");
                }
            }
        }
    }

    private void tryStartMuxer() {
        if (mVTrackIndex != -1  // Video track is added
                && mATrackIndex != -1 // and audio track is added
                && !mMuxerStarted) { // and muxer not started
            // then start the muxer
            mMuxer.start();
            mMuxerStarted = true;
            mRecordStartedAt = System.currentTimeMillis();
        }
    }
}
