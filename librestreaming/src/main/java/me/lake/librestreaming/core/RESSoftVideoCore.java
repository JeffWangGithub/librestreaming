package me.lake.librestreaming.core;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import me.lake.librestreaming.client.CallbackDelivery;
import me.lake.librestreaming.core.listener.RESScreenShotListener;
import me.lake.librestreaming.core.listener.RESVideoChangeListener;
import me.lake.librestreaming.encoder.VideoEncoder;
import me.lake.librestreaming.filter.softvideofilter.BaseSoftVideoFilter;
import me.lake.librestreaming.model.RESConfig;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.model.RESVideoBuff;
import me.lake.librestreaming.render.GLESRender;
import me.lake.librestreaming.render.IRender;
import me.lake.librestreaming.render.NativeRender;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.tools.BuffSizeCalculator;
import me.lake.librestreaming.tools.LogTools;

/**
 * Created by lake on 16-5-24.
 */
public class RESSoftVideoCore implements RESVideoCore {
    RESCoreParameters resCoreParameters;
    private final Object syncOp = new Object();
    private SurfaceTexture cameraTexture;

    private int currentCamera;
    private boolean isEncoderStarted;
    private final Object syncDstVideoEncoder = new Object();
    //render
    private final Object syncPreview = new Object();
    private IRender previewRender;
    //filter
    private Lock lockVideoFilter;
    private BaseSoftVideoFilter videoFilter;
    private VideoEncoderFilterHandler videoEncoderFilterHandler;
    private HandlerThread videoFilterHandlerThread;
    //sender
    private VideoEncoder videoEncoder;
    //VideoBuffs
    //buffers to handle buff from queueVideo
    private RESVideoBuff[] orignVideoBuffs;
    private int lastVideoQueueBuffIndex;
    //buffer to convert orignVideoBuff to NV21 if filter are set
    private RESVideoBuff orignNV21VideoBuff;
    //buffer to handle filtered color from filter if filter are set
    private RESVideoBuff filteredNV21VideoBuff;
    //buffer to convert other color format to suitable color format for dstVideoEncoder if nessesary
    private RESVideoBuff suitable4VideoEncoderBuff;

    final private Object syncResScreenShotListener = new Object();
    private RESScreenShotListener resScreenShotListener;

    private final Object syncIsLooping = new Object();
    private boolean isPreviewing = false;
    private boolean isStreaming = false;
    private int loopingInterval;

    public RESSoftVideoCore(RESCoreParameters parameters) {
        resCoreParameters = parameters;
        lockVideoFilter = new ReentrantLock(false);
        videoFilter = null;
    }

    public void setCurrentCamera(int camIndex) {
        if (currentCamera != camIndex) {
            synchronized (syncOp) {
                if (videoEncoderFilterHandler != null) {
                    videoEncoderFilterHandler.removeMessages(VideoEncoderFilterHandler.WHAT_INCOMING_BUFF);
                }
                if (orignVideoBuffs != null) {
                    for (RESVideoBuff buff : orignVideoBuffs) {
                        buff.isReadyToFill = true;
                    }
                    lastVideoQueueBuffIndex = 0;
                }
            }
        }
        currentCamera = camIndex;
    }

    @Override
    public boolean prepare(RESConfig resConfig) {
        synchronized (syncOp) {
            resCoreParameters.renderingMode = resConfig.getRenderingMode();
            resCoreParameters.mediacdoecAVCBitRate = resConfig.getBitRate();
            resCoreParameters.videoBufferQueueNum = resConfig.getVideoBufferQueueNum();
            resCoreParameters.mediacodecAVCIFrameInterval = resConfig.getVideoGOP();
            resCoreParameters.mediacodecAVCFrameRate = resCoreParameters.videoFPS;
            loopingInterval = 1000 / resCoreParameters.videoFPS;
            synchronized (syncDstVideoEncoder) {
                isEncoderStarted = false;
            }
            resCoreParameters.previewBufferSize = BuffSizeCalculator.calculator(resCoreParameters.videoWidth,
                    resCoreParameters.videoHeight, resCoreParameters.previewColorFormat);
            //video
            int videoWidth = resCoreParameters.videoWidth;
            int videoHeight = resCoreParameters.videoHeight;
            int videoQueueNum = resCoreParameters.videoBufferQueueNum;
            orignVideoBuffs = new RESVideoBuff[videoQueueNum];
            for (int i = 0; i < videoQueueNum; i++) {
                orignVideoBuffs[i] = new RESVideoBuff(resCoreParameters.previewColorFormat, resCoreParameters.previewBufferSize);
            }
            lastVideoQueueBuffIndex = 0;
            orignNV21VideoBuff = new RESVideoBuff(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    BuffSizeCalculator.calculator(videoWidth, videoHeight, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar));
            filteredNV21VideoBuff = new RESVideoBuff(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    BuffSizeCalculator.calculator(videoWidth, videoHeight, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar));
            suitable4VideoEncoderBuff = new RESVideoBuff(resCoreParameters.mediacodecAVCColorFormat,
                    BuffSizeCalculator.calculator(videoWidth, videoHeight, resCoreParameters.mediacodecAVCColorFormat));
            videoFilterHandlerThread = new HandlerThread("videoFilterHandlerThread");
            videoFilterHandlerThread.start();
            videoEncoderFilterHandler = new VideoEncoderFilterHandler(videoFilterHandlerThread.getLooper());
            return true;
        }
    }

    @Override
    public boolean startStreaming(RESFlvDataCollecter flvDataCollecter) {
        synchronized (syncOp) {
            try {
                synchronized (syncDstVideoEncoder) {
                    videoEncoder = new VideoEncoder(resCoreParameters, flvDataCollecter);
                    videoEncoder.start();
                    isEncoderStarted = true;
                }
                synchronized (syncIsLooping) {
                    if (!isPreviewing && !isStreaming) {
                        videoEncoderFilterHandler.removeMessages(VideoEncoderFilterHandler.WHAT_DRAW);
                        videoEncoderFilterHandler.sendMessageDelayed(videoEncoderFilterHandler.obtainMessage(VideoEncoderFilterHandler.WHAT_DRAW, SystemClock.uptimeMillis() + loopingInterval), loopingInterval);
                    }
                    isStreaming = true;
                }
            } catch (Exception e) {
                LogTools.trace("RESVideoClient.start()failed", e);
                return false;
            }
            return true;
        }
    }

    @Override
    public void updateCamTexture(SurfaceTexture camTex) {
    }

    @Override
    public boolean stopStreaming() {
        synchronized (syncOp) {
            synchronized (syncDstVideoEncoder) {
                if (videoEncoder != null) {
                    videoEncoder.quit();
                }
                isEncoderStarted = false;
            }
            synchronized (syncIsLooping) {
                isStreaming = false;
            }
            videoEncoder = null;
            return true;
        }
    }


    @Override
    public boolean destroy() {
        synchronized (syncOp) {
            lockVideoFilter.lock();
            if (videoFilter != null) {
                videoFilter.onDestroy();
            }
            lockVideoFilter.unlock();
            return true;
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void reSetVideoBitrate(int bitrate) {
        synchronized (syncOp) {
            if (videoEncoderFilterHandler != null) {
                resCoreParameters.mediacdoecAVCBitRate = bitrate;
                videoEncoderFilterHandler.sendMessage(videoEncoderFilterHandler.obtainMessage(VideoEncoderFilterHandler.WHAT_RESET_BITRATE, bitrate, 0));
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public int getVideoBitrate() {
        synchronized (syncOp) {
            return resCoreParameters.mediacdoecAVCBitRate;
        }
    }

    @Override
    public void reSetVideoFPS(int fps) {
        synchronized (syncOp) {
            resCoreParameters.videoFPS = fps;
            loopingInterval = 1000 / resCoreParameters.videoFPS;
        }
    }

    @Override
    public void reSetVideoSize(RESCoreParameters newParameters) {

    }

    @Override
    public void startPreview(SurfaceTexture surfaceTexture, int visualWidth, int visualHeight) {
        synchronized (syncPreview) {
            if (previewRender != null) {
                throw new RuntimeException("startPreview without destroy previous");
            }
            switch (resCoreParameters.renderingMode) {
                case RESCoreParameters.RENDERING_MODE_NATIVE_WINDOW:
                    previewRender = new NativeRender();
                    break;
                case RESCoreParameters.RENDERING_MODE_OPENGLES:
                    previewRender = new GLESRender();
                    break;
                default:
                    throw new RuntimeException("Unknow rendering mode");
            }
            previewRender.create(surfaceTexture,
                    resCoreParameters.previewColorFormat,
                    resCoreParameters.videoWidth,
                    resCoreParameters.videoHeight,
                    visualWidth,
                    visualHeight);
            synchronized (syncIsLooping) {
                if (!isPreviewing && !isStreaming) {
                    videoEncoderFilterHandler.removeMessages(VideoEncoderFilterHandler.WHAT_DRAW);
                    videoEncoderFilterHandler.sendMessageDelayed(videoEncoderFilterHandler.obtainMessage(VideoEncoderFilterHandler.WHAT_DRAW, SystemClock.uptimeMillis() + loopingInterval), loopingInterval);
                }
                isPreviewing = true;
            }
        }
    }

    @Override
    public void updatePreview(int visualWidth, int visualHeight) {
        synchronized (syncPreview) {
            if (previewRender == null) {
                throw new RuntimeException("updatePreview without startPreview");
            }
            previewRender.update(visualWidth, visualHeight);
        }
    }

    @Override
    public void stopPreview(boolean releaseTexture) {
        synchronized (syncPreview) {
            if (previewRender == null) {
                throw new RuntimeException("stopPreview without startPreview");
            }
            previewRender.destroy(releaseTexture);
            previewRender = null;
            synchronized (syncIsLooping) {
                isPreviewing = false;
            }
        }
    }

    public void queueVideo(byte[] rawVideoFrame) {
        synchronized (syncOp) {
            int targetIndex = (lastVideoQueueBuffIndex + 1) % orignVideoBuffs.length;
            if (orignVideoBuffs[targetIndex].isReadyToFill) {
                LogTools.d("queueVideo,accept ,targetIndex" + targetIndex);
                acceptVideo(rawVideoFrame, orignVideoBuffs[targetIndex].buff);
                orignVideoBuffs[targetIndex].isReadyToFill = false;
                lastVideoQueueBuffIndex = targetIndex;
                videoEncoderFilterHandler.sendMessage(videoEncoderFilterHandler.obtainMessage(VideoEncoderFilterHandler.WHAT_INCOMING_BUFF, targetIndex, 0));
            } else {
                LogTools.d("queueVideo,abandon,targetIndex" + targetIndex);
            }
        }
    }


    private void acceptVideo(byte[] src, byte[] dst) {
        int directionFlag = currentCamera == Camera.CameraInfo.CAMERA_FACING_BACK ? resCoreParameters.backCameraDirectionMode : resCoreParameters.frontCameraDirectionMode;
        ColorHelper.NV21Transform(src,
                dst,
                resCoreParameters.previewVideoWidth,
                resCoreParameters.previewVideoHeight,
                directionFlag);
    }

    public BaseSoftVideoFilter acquireVideoFilter() {
        lockVideoFilter.lock();
        return videoFilter;
    }

    public void releaseVideoFilter() {
        lockVideoFilter.unlock();
    }

    public void setVideoFilter(BaseSoftVideoFilter baseSoftVideoFilter) {
        lockVideoFilter.lock();
        if (videoFilter != null) {
            videoFilter.onDestroy();
        }
        videoFilter = baseSoftVideoFilter;
        if (videoFilter != null) {
            videoFilter.onInit(resCoreParameters.videoWidth, resCoreParameters.videoHeight);
        }
        lockVideoFilter.unlock();
    }

    @Override
    public void takeScreenShot(RESScreenShotListener listener) {
        synchronized (syncResScreenShotListener) {
            resScreenShotListener = listener;
        }
    }

    @Override
    public void setVideoChangeListener(RESVideoChangeListener listener) {
    }

    @Override
    public float getDrawFrameRate() {
        synchronized (syncOp) {
            return videoEncoderFilterHandler == null ? 0 : videoEncoderFilterHandler.getDrawFrameRate();
        }
    }

    //worker handler
    private class VideoEncoderFilterHandler extends Handler {
        public static final int FILTER_LOCK_TOLERATION = 3;//3ms
        public static final int WHAT_INCOMING_BUFF = 1;
        public static final int WHAT_DRAW = 2;
        public static final int WHAT_RESET_BITRATE = 3;
        private int sequenceNum;
        private RESFrameRateMeter drawFrameRateMeter;

        VideoEncoderFilterHandler(Looper looper) {
            super(looper);
            sequenceNum = 0;
            drawFrameRateMeter = new RESFrameRateMeter();
        }

        public float getDrawFrameRate() {
            return drawFrameRateMeter.getFps();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WHAT_INCOMING_BUFF: {
                    int targetIndex = msg.arg1;
                    /**
                     * orignVideoBuffs[targetIndex] is ready
                     * orignVideoBuffs[targetIndex]->orignNV21VideoBuff
                     */
                    System.arraycopy(orignVideoBuffs[targetIndex].buff, 0,
                            orignNV21VideoBuff.buff, 0, orignNV21VideoBuff.buff.length);
                    orignVideoBuffs[targetIndex].isReadyToFill = true;
                }
                break;
                case WHAT_DRAW: {
                    long time = (Long) msg.obj;
                    long interval = time + loopingInterval - SystemClock.uptimeMillis();
                    synchronized (syncIsLooping) {
                        if (isPreviewing || isStreaming) {
                            if (interval > 0) {
                                videoEncoderFilterHandler.sendMessageDelayed(videoEncoderFilterHandler.obtainMessage(
                                                VideoEncoderFilterHandler.WHAT_DRAW,
                                                SystemClock.uptimeMillis() + interval),
                                        interval);
                            } else {
                                videoEncoderFilterHandler.sendMessage(videoEncoderFilterHandler.obtainMessage(
                                        VideoEncoderFilterHandler.WHAT_DRAW,
                                        SystemClock.uptimeMillis() + loopingInterval));
                            }
                        }
                    }
                    sequenceNum++;
                    long nowTimeMs = SystemClock.uptimeMillis();
                    boolean isFilterLocked = lockVideoFilter();
                    if (isFilterLocked) {
                        boolean modified;
                        modified = videoFilter.onFrame(orignNV21VideoBuff.buff, filteredNV21VideoBuff.buff, nowTimeMs, sequenceNum);
                        unlockVideoFilter();
                        rendering(modified ? filteredNV21VideoBuff.buff : orignNV21VideoBuff.buff);
                        checkScreenShot(modified ? filteredNV21VideoBuff.buff : orignNV21VideoBuff.buff);
                        /**
                         * orignNV21VideoBuff is ready
                         * orignNV21VideoBuff->suitable4VideoEncoderBuff
                         */
                        if (resCoreParameters.mediacodecAVCColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                            ColorHelper.NV21TOYUV420SP(modified ? filteredNV21VideoBuff.buff : orignNV21VideoBuff.buff,
                                    suitable4VideoEncoderBuff.buff, resCoreParameters.videoWidth * resCoreParameters.videoHeight);
                        } else if (resCoreParameters.mediacodecAVCColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                            ColorHelper.NV21TOYUV420P(modified ? filteredNV21VideoBuff.buff : orignNV21VideoBuff.buff,
                                    suitable4VideoEncoderBuff.buff, resCoreParameters.videoWidth * resCoreParameters.videoHeight);
                        } else {//LAKETODO colorConvert
                        }
                    } else {
                        rendering(orignNV21VideoBuff.buff);
                        checkScreenShot(orignNV21VideoBuff.buff);
                        if (resCoreParameters.mediacodecAVCColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                            ColorHelper.NV21TOYUV420SP(orignNV21VideoBuff.buff,
                                    suitable4VideoEncoderBuff.buff,
                                    resCoreParameters.videoWidth * resCoreParameters.videoHeight);
                        } else if (resCoreParameters.mediacodecAVCColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                            ColorHelper.NV21TOYUV420P(orignNV21VideoBuff.buff,
                                    suitable4VideoEncoderBuff.buff,
                                    resCoreParameters.videoWidth * resCoreParameters.videoHeight);
                        }
                        orignNV21VideoBuff.isReadyToFill = true;
                    }
                    drawFrameRateMeter.count();
                    //suitable4VideoEncoderBuff is ready
                    synchronized (syncDstVideoEncoder) {
                        if (videoEncoder != null) {
                            videoEncoder.queueData(suitable4VideoEncoderBuff.buff, nowTimeMs);
                        }
                    }

                    LogTools.d("VideoFilterHandler,ProcessTime:" + (System.currentTimeMillis() - nowTimeMs));
                }
                break;
                case WHAT_RESET_BITRATE: {
                    if (videoEncoder != null) {
                        videoEncoder.resetBitRate(msg.arg1);
                    }
                }
                break;
            }
        }

        /**
         * rendering nv21 using native window
         *
         * @param pixel
         */
        private void rendering(byte[] pixel) {
            synchronized (syncPreview) {
                if (previewRender == null) {
                    return;
                }
                previewRender.rendering(pixel);
            }
        }

        /**
         * check if screenshotlistener exist
         *
         * @param pixel
         */
        private void checkScreenShot(byte[] pixel) {
            synchronized (syncResScreenShotListener) {
                if (resScreenShotListener != null) {
                    int[] argbPixel = new int[resCoreParameters.videoWidth * resCoreParameters.videoHeight];
                    ColorHelper.NV21TOARGB(pixel,
                            argbPixel,
                            resCoreParameters.videoWidth,
                            resCoreParameters.videoHeight);
                    Bitmap result = Bitmap.createBitmap(argbPixel,
                            resCoreParameters.videoWidth,
                            resCoreParameters.videoHeight,
                            Bitmap.Config.ARGB_8888);
                    CallbackDelivery.i().post(new RESScreenShotListener.RESScreenShotListenerRunable(resScreenShotListener, result));
                    resScreenShotListener = null;
                }
            }
        }

        /**
         * @return ture if filter locked & filter!=null
         */

        private boolean lockVideoFilter() {
            try {
                boolean locked = lockVideoFilter.tryLock(FILTER_LOCK_TOLERATION, TimeUnit.MILLISECONDS);
                if (locked) {
                    if (videoFilter != null) {
                        return true;
                    } else {
                        lockVideoFilter.unlock();
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

        private void unlockVideoFilter() {
            lockVideoFilter.unlock();
        }
    }
}
