package com.mingmyal.radialmenu.mixin;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.util.ImportedSoundStore;

import com.mojang.blaze3d.audio.SoundBuffer;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sound.sampled.AudioFormat;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.Util;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 对 radialmenu:sounds/custom.* 路径, 从 config/radialmenu/sounds/ 加载音频文件
 * (.mp3 用 JLayer 解码, .ogg 用原版 JOrbis 解码),
 * 使其不依赖资源包即可被 SoundEngine 播放。
 */
@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {

    private static final String CUSTOM_PATH_PREFIX = "sounds/custom.";

    @Inject(
            method = "getCompleteBuffer",
            at = @At("HEAD"),
            cancellable = true)
    private void radialmenu$loadCustomSoundBuffer(
            ResourceLocation location,
            CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        if (location == null) return;
        if (!"radialmenu".equals(location.getNamespace())) return;

        String path = location.getPath();
        if (!path.startsWith(CUSTOM_PATH_PREFIX) || !path.endsWith(".ogg")) {
            return;
        }

        String name = path.substring(CUSTOM_PATH_PREFIX.length(), path.length() - 4);
        File file = ImportedSoundStore.getSoundFile(name);
        if (file == null || !file.isFile()) {
            RadialCommon.LOGGER.warn("Imported sound file not found: {}", file);
            return;
        }

        CompletableFuture<SoundBuffer> future = CompletableFuture.supplyAsync(() -> {
            try {
                return file.getName().toLowerCase().endsWith(".mp3") ? decodeMp3(file) : decodeOgg(file);
            } catch (Exception e) {
                throw new CompletionException(
                        "Failed to load imported sound: " + file, e);
            }
        }, Util.nonCriticalIoPool());

        cir.setReturnValue(future);
    }

    private static SoundBuffer decodeOgg(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            return new SoundBuffer(stream.readAll(), stream.getFormat());
        }
    }

    /** 用 JLayer 将 mp3 解码为 16 位小端 PCM (原版声音引擎仅内置 OGG 解码)。 */
    private static SoundBuffer decodeMp3(File file) throws Exception {
        Bitstream bitstream = new Bitstream(openMp3Stream(file));
        try {
            Decoder decoder = new Decoder();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            int sampleRate = -1;
            int channels = -1;
            Header header;
            try {
                while ((header = bitstream.readFrame()) != null) {
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    short[] samples = output.getBuffer();
                    int length = output.getBufferLength();
                    byte[] bytes = new byte[length * 2];
                    for (int i = 0; i < length; i++) {
                        bytes[i * 2] = (byte) (samples[i] & 0xFF);
                        bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
                    }
                    pcm.write(bytes);
                    sampleRate = decoder.getOutputFrequency();
                    channels = decoder.getOutputChannels();
                    bitstream.closeFrame();
                }
            } catch (BitstreamException e) {
                // 文件末尾 ID3v1 等非帧数据会触发解析错误, 已解码内容仍然可用
                if (pcm.size() == 0) {
                    throw e;
                }
            }
            if (sampleRate <= 0 || channels <= 0) {
                throw new IOException("No MP3 frames decoded: " + file);
            }
            AudioFormat format = new AudioFormat((float) sampleRate, 16, channels, true, false);
            // OpenAL 的 nalBufferData 按原生内存地址读取, 必须用直接内存:
            // 堆 ByteBuffer 的原生地址为 0, 会导致 OpenAL.dll 原生崩溃 (EXCEPTION_ACCESS_VIOLATION)
            byte[] bytes = pcm.toByteArray();
            ByteBuffer data = ByteBuffer.allocateDirect(bytes.length);
            data.put(bytes);
            data.flip();
            return new SoundBuffer(data, format);
        } finally {
            try {
                bitstream.close();
            } catch (BitstreamException ignored) {
            }
        }
    }

    /** 打开 MP3 输入流, 自动跳过开头的 ID3v2 标签 (JLayer 不识别)。 */
    private static InputStream openMp3Stream(File file) throws Exception {
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 65536);
        in.mark(10);
        byte[] head = new byte[10];
        int read = 0;
        while (read < 10) {
            int r = in.read(head, read, 10 - read);
            if (r < 0) break;
            read += r;
        }
        if (read == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            // ID3v2 标签长度为 syncsafe 编码 (每字节仅低 7 位有效)
            long size = ((head[6] & 0x7FL) << 21)
                    | ((head[7] & 0x7FL) << 14)
                    | ((head[8] & 0x7FL) << 7)
                    | (head[9] & 0x7FL);
            long remaining = size;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) break;
                remaining -= skipped;
            }
            return in;
        }
        in.reset();
        return in;
    }
}
