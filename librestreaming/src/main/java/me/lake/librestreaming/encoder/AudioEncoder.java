package me.lake.librestreaming.encoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;

import me.lake.librestreaming.core.MediaCodecHelper;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.rtmp.RESFlvData;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.rtmp.RESRtmpSender;
import me.lake.librestreaming.tools.LogTools;

/**
 * Created by lakeinchina on 26/05/16.
 */
public class AudioEncoder {
    private static final long WAIT_TIME = -1;//1ms;
    private long startTime;
    private MediaCodec dstAudioEncoder;
    private RESFlvDataCollecter dataCollecter;
    private boolean shouldQuit = false;
    private RESCoreParameters resCoreParameters;
    private MediaFormat dstAudioFormat;

    public AudioEncoder(RESCoreParameters resCoreParameters, RESFlvDataCollecter flvDataCollecter) {
        startTime = System.currentTimeMillis();
        dataCollecter = flvDataCollecter;
        dstAudioFormat = new MediaFormat();
        dstAudioEncoder = MediaCodecHelper.createAudioMediaCodec(resCoreParameters, dstAudioFormat);
        if (dstAudioEncoder != null) {
            dstAudioEncoder.configure(dstAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
    }

    public void start() {
        shouldQuit = false;
        dstAudioEncoder.start();
        run();
    }

    public void quit() {
        shouldQuit = true;
    }

    public void queueData(byte[] data, int length, long timeMs) {
        //orignAudioBuff is ready
        int eibIndex = dstAudioEncoder.dequeueInputBuffer(-1);
        if (eibIndex >= 0) {
            ByteBuffer inputBuffer = dstAudioEncoder.getInputBuffer(eibIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();
                int bufferRemaining = inputBuffer.remaining();
                //剩余buffer大小
                inputBuffer.put(data, 0, Math.min(bufferRemaining, length));
                dstAudioEncoder.queueInputBuffer(eibIndex, 0, inputBuffer.position(), timeMs * 1000, 0);
            }
        } else {
            LogTools.d("dstAudioEncoder.dequeueInputBuffer(-1)<0");
        }
    }

    private void run() {
        HandlerThread audioEncoderThread = new HandlerThread("Audio Encoder Thread");
        audioEncoderThread.start();

        Handler handler = new Handler(audioEncoderThread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                encode();
            }
        });
    }

    private void encode() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!shouldQuit) {
            int outputBufferIndex = dstAudioEncoder.dequeueOutputBuffer(bufferInfo, WAIT_TIME);
            if (outputBufferIndex > 0) {
                ByteBuffer encodedData = dstAudioEncoder.getOutputBuffer(outputBufferIndex);
                if (encodedData == null) {
                    continue;
                }
                encodedData.position(bufferInfo.offset);
                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                byte[] data = new byte[bufferInfo.size];
                encodedData.get(data, 0, bufferInfo.size);
//                encodedData.position(bufferInfo.offset);

                long time = System.currentTimeMillis() - startTime;
                sendRealData(time, data);

                dstAudioEncoder.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ByteBuffer byteBuffer = dstAudioEncoder.getOutputFormat().getByteBuffer("csd-0");
                if (byteBuffer != null) {
                    byte[] data = new byte[byteBuffer.remaining()];
                    byteBuffer.get(data, 0, byteBuffer.remaining());
                    long time = System.currentTimeMillis() - startTime;
                    sendAudioSpecificConfig(time, data);
                }
            }
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                //end of stream
                break;
            }
        }
        release();
    }

    private void release() {
        shouldQuit = true;
        dstAudioEncoder.stop();
        dstAudioEncoder.release();
        dstAudioEncoder = null;
    }

    private void sendAudioSpecificConfig(long tms, byte[] data) {
        //不发送此信息可能导致拉流播放失败
        RESFlvData resFlvData = new RESFlvData();
        resFlvData.droppable = false;
        resFlvData.byteBuffer = data;
        resFlvData.size = data.length;
        resFlvData.dts = (int) tms;
        resFlvData.flvTagType = RESFlvData.FLV_RTMP_PACKET_TYPE_AUDIO;
        dataCollecter.collect(resFlvData, RESRtmpSender.FROM_AUDIO);
    }

    private void sendRealData(long tms, byte[] data) {
        RESFlvData resFlvData = new RESFlvData();
        resFlvData.droppable = true;
        resFlvData.byteBuffer = data;
        resFlvData.size = data.length;
        resFlvData.dts = (int) tms;
        resFlvData.flvTagType = RESFlvData.FLV_RTMP_PACKET_TYPE_AUDIO;
        dataCollecter.collect(resFlvData, RESRtmpSender.FROM_AUDIO);
    }
}
