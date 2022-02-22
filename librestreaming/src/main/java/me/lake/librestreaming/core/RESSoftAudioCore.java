package me.lake.librestreaming.core;

import android.media.MediaCodecInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import me.lake.librestreaming.encoder.AudioEncoder;
import me.lake.librestreaming.filter.softaudiofilter.BaseSoftAudioFilter;
import me.lake.librestreaming.model.RESConfig;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.tools.LogTools;

/**
 * Created by lake on 16-5-24.
 */
public class RESSoftAudioCore {
    RESCoreParameters resCoreParameters;
    private final Object syncOp = new Object();
    //filter
    private Lock lockAudioFilter;
    private BaseSoftAudioFilter audioFilter;
    private AudioEncodeFilterHandler audioEncodeFilterHandler;
    private HandlerThread audioFilterHandlerThread;
    private AudioEncoder audioEncoder;
    private boolean isStarting = false;

    public RESSoftAudioCore(RESCoreParameters parameters) {
        resCoreParameters = parameters;
        lockAudioFilter = new ReentrantLock(false);
    }

    public void queueAudio(byte[] rawAudioFrame, int size) {
        //TODO此处需要添加buffer的逻辑
        if (isStarting) {
            audioEncodeFilterHandler.sendMessage(audioEncodeFilterHandler.obtainMessage(AudioEncodeFilterHandler.WHAT_INCOMING_BUFF, size, 0, rawAudioFrame));
        }
    }

    public boolean prepare(RESConfig resConfig) {
        synchronized (syncOp) {
            resCoreParameters.mediacodecAACProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
            resCoreParameters.mediacodecAACSampleRate = 44100;
            resCoreParameters.mediacodecAACChannelCount = 1;
            resCoreParameters.mediacodecAACBitRate = 32 * 1024;
            resCoreParameters.mediacodecAACMaxInputSize = 8820;
            return true;
        }
    }

    public void start(RESFlvDataCollecter flvDataCollecter) {
        synchronized (syncOp) {
            try {
                //每次start重新创建
                audioEncoder = new AudioEncoder(resCoreParameters, flvDataCollecter);
                audioEncoder.start();

                audioFilterHandlerThread = new HandlerThread("audioFilterHandlerThread");
                audioFilterHandlerThread.start();
                audioEncodeFilterHandler = new AudioEncodeFilterHandler(audioFilterHandlerThread.getLooper());

                isStarting = true;
            } catch (Exception e) {
                LogTools.trace("RESSoftAudioCore", e);
            }
        }
    }

    public void stop() {
        synchronized (syncOp) {
            isStarting = false;
            audioEncodeFilterHandler.removeCallbacksAndMessages(null);
            audioFilterHandlerThread.quit();
            try {
                audioFilterHandlerThread.join();
                audioEncoder.quit();
            } catch (InterruptedException e) {
                LogTools.trace("RESSoftAudioCore", e);
            }
        }
    }

    public BaseSoftAudioFilter acquireAudioFilter() {
        lockAudioFilter.lock();
        return audioFilter;
    }

    public void releaseAudioFilter() {
        lockAudioFilter.unlock();
    }

    public void setAudioFilter(BaseSoftAudioFilter baseSoftAudioFilter) {
        lockAudioFilter.lock();
        if (audioFilter != null) {
            audioFilter.onDestroy();
        }
        audioFilter = baseSoftAudioFilter;
        if (audioFilter != null) {
            audioFilter.onInit(resCoreParameters.mediacodecAACSampleRate / 5);
        }
        lockAudioFilter.unlock();
    }

    public void destroy() {
        synchronized (syncOp) {
            lockAudioFilter.lock();
            if (audioFilter != null) {
                audioFilter.onDestroy();
            }
            lockAudioFilter.unlock();
        }
    }

    private class AudioEncodeFilterHandler extends Handler {
        public static final int FILTER_LOCK_TOLERATION = 3;//3ms
        public static final int WHAT_INCOMING_BUFF = 1;
        private int sequenceNum;

        AudioEncodeFilterHandler(Looper looper) {
            super(looper);
            sequenceNum = 0;
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != WHAT_INCOMING_BUFF) {
                return;
            }
            sequenceNum++;
            long nowTimeMs = System.currentTimeMillis();
            byte[] orginBufferData = (byte[]) msg.obj;
            int length = msg.arg1;
            boolean isFilterLocked = lockAudioFilter();
            boolean filtered;
            if (isFilterLocked) {
                byte[] filteredData = new byte[orginBufferData.length];
                filtered = audioFilter.onFrame(orginBufferData, filteredData, nowTimeMs, sequenceNum);
                unlockAudioFilter();
                if (filtered) {
                    orginBufferData = filteredData;
                }
            }
            //orignAudioBuff is ready
            audioEncoder.queueData(orginBufferData, length, nowTimeMs);
            LogTools.d("AudioFilterHandler,ProcessTime:" + (System.currentTimeMillis() - nowTimeMs));
        }

        /**
         * @return ture if filter locked & filter!=null
         */

        private boolean lockAudioFilter() {
            try {
                boolean locked = lockAudioFilter.tryLock(FILTER_LOCK_TOLERATION, TimeUnit.MILLISECONDS);
                if (locked) {
                    if (audioFilter != null) {
                        return true;
                    } else {
                        lockAudioFilter.unlock();
                        return false;
                    }
                } else {
                    return false;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return false;
        }

        private void unlockAudioFilter() {
            lockAudioFilter.unlock();
        }
    }
}
