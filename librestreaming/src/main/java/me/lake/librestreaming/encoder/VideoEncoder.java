package me.lake.librestreaming.encoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;

import me.lake.librestreaming.core.MediaCodecHelper;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.rtmp.RESFlvData;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.rtmp.RESRtmpSender;
import me.lake.librestreaming.tools.LogTools;

/**
 * 主要负责encode源数据，封装成RESFlvData
 */
public class VideoEncoder {
    private static final long WAIT_TIME = 5000;
    private MediaCodec.BufferInfo eInfo;
    private long startTime;
    private MediaCodec dstVideoEncoder;
    private final Object syncDstVideoEncoder = new Object();
    private RESFlvDataCollecter dataCollecter;
    private boolean shouldQuit = false;
    private RESCoreParameters resCoreParameters;
    private MediaFormat dstVideoFormat;

    public VideoEncoder(RESCoreParameters resCoreParameters, RESFlvDataCollecter flvDataCollecter) {
        this.resCoreParameters = resCoreParameters;
        eInfo = new MediaCodec.BufferInfo();
        startTime = 0;
        dataCollecter = flvDataCollecter;
        dstVideoFormat = new MediaFormat();
        dstVideoEncoder = MediaCodecHelper.createHardVideoMediaCodec(resCoreParameters, dstVideoFormat);
        if (dstVideoEncoder == null) {
            throw new RuntimeException("create Video MediaCodec failed");
        }
        dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    public Surface getInputSurface() {
        return dstVideoEncoder.createInputSurface();
    }


    public void resetBitRate(int bitrate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Bundle bitrateBundle = new Bundle();
            bitrateBundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            dstVideoEncoder.setParameters(bitrateBundle);
        }
    }

    public void start() {
        shouldQuit = false;
        dstVideoEncoder.start();
        run();
    }

    public void quit() {
        shouldQuit = true;
    }

    public void queueData(byte[] data, long timeMs) {
        if (dstVideoEncoder != null && !shouldQuit) {
            int eibIndex = dstVideoEncoder.dequeueInputBuffer(-1);
            if (eibIndex >= 0) {
                ByteBuffer dstVideoEncoderIBuffer = dstVideoEncoder.getInputBuffers()[eibIndex];
                dstVideoEncoderIBuffer.position(0);
                dstVideoEncoderIBuffer.put(data, 0, data.length);
                dstVideoEncoder.queueInputBuffer(eibIndex, 0, data.length, timeMs * 1000, 0);
            } else {
                LogTools.d("dstVideoEncoder.dequeueInputBuffer(-1)<0");
            }
        }
    }

    private void run() {
        HandlerThread videoEncodeThread = new HandlerThread("video encode thread");
        videoEncodeThread.start();

        Handler handler = new Handler(videoEncodeThread.getLooper());

        handler.post(new Runnable() {
            @Override
            public void run() {
                encode();
            }
        });
    }

    private void encode() {
        while (!shouldQuit) {
            synchronized (syncDstVideoEncoder) {
                int eobIndex = MediaCodec.INFO_TRY_AGAIN_LATER;
                try {
                    eobIndex = dstVideoEncoder.dequeueOutputBuffer(eInfo, WAIT_TIME);
                } catch (Exception ignored) {
                }

                if (eobIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    LogTools.d("VideoSenderThread,MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:" +
                                dstVideoEncoder.getOutputFormat());
                        sendAVCDecoderConfigurationRecord(0, dstVideoEncoder.getOutputFormat());
                } else {
                    if (eobIndex > 0 && dstVideoEncoder.getOutputBuffers() != null) {
                        LogTools.d("VideoSenderThread,MediaCode,eobIndex=" + eobIndex);
                        if (startTime == 0) {
                            startTime = eInfo.presentationTimeUs / 1000;
                        }
                        /**
                         * we send sps pps already in INFO_OUTPUT_FORMAT_CHANGED
                         * so we ignore MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                         */
                        if (eInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG && eInfo.size != 0) {
                            ByteBuffer realData = dstVideoEncoder.getOutputBuffers()[eobIndex];
                            realData.position(eInfo.offset);
                            realData.limit(eInfo.offset + eInfo.size);
                            realData.position(eInfo.offset);
                            sendRealData((eInfo.presentationTimeUs / 1000) - startTime, realData);
                        }
                        dstVideoEncoder.releaseOutputBuffer(eobIndex, false);
                    } else if (eobIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        //超时
                        continue;
                    }

                    if ((eInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        }
        release();
        eInfo = null;
    }

    private void release() {
        shouldQuit = true;
        dstVideoEncoder.stop();
        dstVideoEncoder.release();
        dstVideoEncoder = null;
    }

    private void sendAVCDecoderConfigurationRecord(long tms, MediaFormat format) {
        ////不发送此信息可能导致拉流播放失败
        ByteBuffer sps = format.getByteBuffer("csd-0");
        ByteBuffer pps = format.getByteBuffer("csd-1");
        ByteBuffer allocate = ByteBuffer.allocate(sps.limit() + pps.limit());
        allocate.put(sps.array());
        allocate.put(pps.array());
        byte[] config = allocate.array();

        RESFlvData resFlvData = new RESFlvData();
        resFlvData.droppable = false;
        resFlvData.byteBuffer = config;
        resFlvData.size = config.length;
        resFlvData.dts = (int) tms;
        resFlvData.flvTagType = RESFlvData.FLV_RTMP_PACKET_TYPE_VIDEO;
        resFlvData.videoFrameType = RESFlvData.NALU_TYPE_IDR;
        dataCollecter.collect(resFlvData, RESRtmpSender.FROM_VIDEO);
    }

    private void sendRealData(long tms, ByteBuffer realData) {
        int realDataLength = realData.remaining();
        byte[] finalBuff = new byte[realDataLength];
        realData.get(finalBuff, 0, realDataLength);
        RESFlvData resFlvData = new RESFlvData();
        resFlvData.droppable = true;
        resFlvData.byteBuffer = finalBuff;
        resFlvData.size = finalBuff.length;
        resFlvData.dts = (int) tms;
        resFlvData.flvTagType = RESFlvData.FLV_RTMP_PACKET_TYPE_VIDEO;
        dataCollecter.collect(resFlvData, RESRtmpSender.FROM_VIDEO);
    }
}