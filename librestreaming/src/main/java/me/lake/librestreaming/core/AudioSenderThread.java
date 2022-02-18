package me.lake.librestreaming.core;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

import me.lake.librestreaming.rtmp.RESFlvData;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.rtmp.RESRtmpSender;

/**
 * Created by lakeinchina on 26/05/16.
 */
public class AudioSenderThread extends Thread {
    private static final long WAIT_TIME = -1;//1ms;
    private long startTime;
    private MediaCodec dstAudioEncoder;
    private RESFlvDataCollecter dataCollecter;

    AudioSenderThread(String name, MediaCodec encoder, RESFlvDataCollecter flvDataCollecter) {
        super(name);
        startTime = System.currentTimeMillis();
        dstAudioEncoder = encoder;
        dataCollecter = flvDataCollecter;
    }

    private boolean shouldQuit = false;

    void quit() {
        shouldQuit = true;
        this.interrupt();
    }

    @Override
    public void run() {
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
