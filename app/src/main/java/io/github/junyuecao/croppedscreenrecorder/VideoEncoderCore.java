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
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
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
    private static final boolean VERBOSE = false;
    private static final int TIMEOUT_USEC = 10000;
    private static final int DEFAULT_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int DEFAULT_DATA_FORMAT = AudioFormat.ENCODING_PCM_16BIT;


    private static final int SAMPLES_PER_FRAME = 1024;

    // TODO: these ought to be configurable as well
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;    // H.264 Advanced Video Coding
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private Surface mInputSurface;
    private MediaMuxer mMuxer;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mVBufferInfo;
    private MediaCodec.BufferInfo mABufferInfo;
    private int mVTrackIndex;
    private int mATrackIndex;
    private boolean mMuxerStarted;
    private AudioRecord mAudioRecord;

    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public VideoEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {
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
        if (VERBOSE) Log.d(TAG, "videoFormat: " + videoFormat);

        // Create a MediaCodec encoder, and configure it with our videoFormat.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mVideoEncoder.createInputSurface();
        mVideoEncoder.start();

        // init AudioRecord to record from mic
        initAudioRecord(DEFAULT_SOURCE, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_CONFIG, DEFAULT_DATA_FORMAT);

        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE,
                DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_CONFIG);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mVTrackIndex = -1;
        mATrackIndex = -1;
        mMuxerStarted = false;
    }

    public boolean initAudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat) {
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
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mMuxer != null) {
            // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call stop() if we haven't written anything.
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
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
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mVideoEncoder.signalEndOfInputStream();
        }

        drainVideo(endOfStream);

        drainAudio(endOfStream);
    }

    private void drainVideo(boolean endOfStream) {
        ByteBuffer[] encoderOutputBuffers = mVideoEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mVideoEncoder.dequeueOutputBuffer(mVBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mVideoEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mVTrackIndex = mMuxer.addTrack(newFormat);
                tryStartMuxer();
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                // same as mVideoEncoder.getOutputBuffer(encoderStatus)
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];

                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mVBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
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
                        Log.d(TAG, "sent " + mVBufferInfo.size + " bytes to muxer, ts=" +
                                mVBufferInfo.presentationTimeUs);
                    }
                }

                mVideoEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mVBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    private void drainAudio(boolean endOfStream) {
        byte[] buffer = new byte[SAMPLES_PER_FRAME * 2];
        int ret = mAudioRecord.read(buffer, 0, buffer.length);
        if (ret == AudioRecord.ERROR_INVALID_OPERATION) {
            Log.e(TAG, "Error ERROR_INVALID_OPERATION");
        } else if (ret == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Error ERROR_BAD_VALUE");
        } else if (ret > 0){
            ByteBuffer[] encoderOutputBuffers = mAudioEncoder.getOutputBuffers();

            boolean done = false;
            while (!done) {
                int index = mAudioEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (index >= 0) { // In case we didn't get any input buffer, it may be blocked by all output buffers being full, thus try to drain them below if we didn't get any
                    ByteBuffer in = encoderOutputBuffers[index];
                    in.clear();
                    in.put(buffer, 0, ret);
                    mAudioEncoder.queueInputBuffer(index, 0, ret, System.nanoTime()/1000, 0);
                    done = true; // Done passing the input to the codec, but still check for available output below
                }
                index = mAudioEncoder.dequeueOutputBuffer(mABufferInfo, TIMEOUT_USEC);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (mATrackIndex != -1) {
                        throw new RuntimeException("format changed twice");
                    }
                    mATrackIndex = mMuxer.addTrack(mAudioEncoder.getOutputFormat());
                    tryStartMuxer();

                } else if (index >= 0) {
                    if ((mABufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // ignore codec config
                        mABufferInfo.size = 0;
                    }
                    if (mATrackIndex != -1 && mABufferInfo.size > 0) {
                        ByteBuffer out = mAudioEncoder.getOutputBuffer(index);
                        out.position(mABufferInfo.offset);
                        out.limit(mABufferInfo.offset + mABufferInfo.size);
                        mMuxer.writeSampleData(mATrackIndex, out, mABufferInfo);
                        mAudioEncoder.releaseOutputBuffer(index, false);
                    }
                }
            }
        }
    }

    private void tryStartMuxer() {
        if (mVTrackIndex != -1 && mATrackIndex != -1) {
            mMuxer.start();
            mMuxerStarted = true;
        }
    }
}
