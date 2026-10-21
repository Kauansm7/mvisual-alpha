package com.switchplayer;

import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.LogCallback;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * SwitchPlayer (Mvisual Alpha) - monitor de captura HDMI de baixa latência (DirectShow/GDI + OpenGL 3.3).
 *
 * Thread "capture": FFmpeg/dshow -> cópia off-heap -> FrameExchange (lock-free triple buffer)
 * Thread "ai-super-resolution" (opcional): inferência assíncrona ONNX sem bloquear captura nem render
 * Thread principal (render): GLFW + OpenGL 3.3 core -> textura -> shader CAS/FXAA/Saturação + Barra de Menus e Painéis OSD
 */
public final class SwitchPlayerApp {

    // ------------------------------------------------------------------ config

    public record Config(
            String device,
            String audioDevice,
            String outputMixer,
            int width,
            int height,
            boolean autoResolution,
            int fps,
            boolean mjpeg,
            float sharp,
            float radius,
            float saturation,
            boolean fxaa,
            String scaleMode,
            boolean aiEnabled,
            String gpuBackend,
            String qualityProfile,
            boolean list,
            boolean modes,
            boolean debug
    ) {
        static Config parse(String[] a) {
            String device = "USB Video";
            String audioDevice = "";
            String outputMixer = "";
            int w = 1920, h = 1080, fps = 60;
            boolean autoRes = false;
            boolean mjpeg = false, list = false, modes = false, debug = false;
            float sharp = 0.6f, radius = 1.0f, saturation = 1.10f;
            boolean fxaa = false;
            String scaleMode = "Preservar proporção";
            boolean aiEnabled = false;
            String gpuBackend = "Automático (recomendado)";
            String qualityProfile = "Equilibrado";
            for (int i = 0; i < a.length; i++) {
                switch (a[i]) {
                    case "--device" -> device = a[++i];
                    case "--audio-device" -> audioDevice = a[++i];
                    case "--output-mixer" -> outputMixer = a[++i];
                    case "--size" -> {
                        String val = a[++i].toLowerCase();
                        if (val.startsWith("auto")) {
                            Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                            w = d.width;
                            h = d.height;
                            autoRes = true;
                        } else {
                            String[] p = val.split("x");
                            w = Integer.parseInt(p[0]);
                            h = Integer.parseInt(p[1]);
                            autoRes = false;
                        }
                    }
                    case "--fps" -> fps = Integer.parseInt(a[++i]);
                    case "--sharp" -> sharp = Float.parseFloat(a[++i]);
                    case "--radius" -> radius = Float.parseFloat(a[++i]);
                    case "--saturation" -> saturation = Float.parseFloat(a[++i]);
                    case "--fxaa" -> fxaa = true;
                    case "--scale" -> scaleMode = a[++i];
                    case "--ai" -> aiEnabled = true;
                    case "--gpu" -> gpuBackend = a[++i];
                    case "--profile" -> qualityProfile = a[++i];
                    case "--mjpeg" -> mjpeg = true;
                    case "--list" -> list = true;
                    case "--modes" -> modes = true;
                    case "--debug" -> debug = true;
                    default -> System.err.println("Argumento ignorado: " + a[i]);
                }
            }
            return new Config(device, audioDevice, outputMixer, w, h, autoRes, fps, mjpeg,
                    sharp, radius, saturation, fxaa, scaleMode, aiEnabled, gpuBackend, qualityProfile, list, modes, debug);
        }

        String dshowInput() {
            if (device.equalsIgnoreCase("desktop")) return "desktop";
            String video = device.startsWith("video=") ? device.substring(6) : device;
            if (audioDevice == null || audioDevice.isBlank() || audioDevice.equals("(Sem Áudio)")) return "video=" + video;
            String audio = audioDevice.startsWith("audio=") ? audioDevice.substring(6) : audioDevice;
            return "video=" + video + ":audio=" + audio;
        }
    }

    // ------------------------------------------- troca de frames sem lock (triple buffer)

    static final class Slot {
        final ByteBuffer buf;
        volatile long seq;

        Slot(int size) {
            buf = MemoryUtil.memAlloc(size);
        }
    }

    static final class FrameExchange {
        final int width, height, bytes;
        private final AtomicReference<Slot> middle;
        private Slot back;
        private Slot front;
        private long counter;
        private final AtomicBoolean freed = new AtomicBoolean(false);

        FrameExchange(int width, int height) {
            this.width = width;
            this.height = height;
            this.bytes = width * height * 3;
            back = new Slot(bytes);
            front = new Slot(bytes);
            middle = new AtomicReference<>(new Slot(bytes));
        }

        ByteBuffer writeBuffer() {
            return back.buf;
        }

        void publish() {
            back.seq = ++counter;
            back = middle.getAndSet(back);
        }

        ByteBuffer acquireLatest() {
            if (freed.get()) return null;
            if (middle.get().seq > front.seq) {
                front = middle.getAndSet(front);
                return front.buf;
            }
            return null;
        }

        void free() {
            if (freed.compareAndSet(false, true)) {
                MemoryUtil.memFree(back.buf);
                MemoryUtil.memFree(front.buf);
                MemoryUtil.memFree(middle.get().buf);
            }
        }
    }

    // --------------------------------------------------------- FFmpeg Log & Swscale Range Fix

    private static LogCallback customAvLogCallback;
    private static final AtomicInteger swsDeprecatedCount = new AtomicInteger(0);

    private static void installFfmpegLogFilter() {
        if (customAvLogCallback != null) return;
        customAvLogCallback = new LogCallback() {
            @Override
            public void call(int level, BytePointer msgPtr) {
                if (msgPtr == null) return;
                String msg = msgPtr.getString();
                if (msg == null) return;
                if (msg.contains("deprecated pixel format used")) {
                    int c = swsDeprecatedCount.incrementAndGet();
                    if (c == 1) {
                        System.out.println("[ffmpeg/swscale] Formato MJPEG full-range (YUVJ) detectado; faixa de cores MPEG/JPEG configurada.");
                    }
                    return;
                }
                if (level <= avutil.AV_LOG_WARNING) {
                    System.err.print(msg);
                }
            }
        };
        customAvLogCallback.retainReference();
        avutil.setLogCallback(customAvLogCallback);
    }

    /**
     * Normaliza os formatos deprecados YUVJ420P/YUVJ422P/YUVJ444P no AVCodecContext e AVFrame
     * do JavaCV para os formatos canônicos YUV*P com color_range = AVCOL_RANGE_JPEG,
     * e aplica sws_setColorspaceDetails quando o SwsContext estiver ativo.
     */
    private static void normalizeGrabberColorRange(FFmpegFrameGrabber grabber) {
        try {
            Field vcField = FFmpegFrameGrabber.class.getDeclaredField("video_c");
            vcField.setAccessible(true);
            AVCodecContext vc = (AVCodecContext) vcField.get(grabber);
            if (vc != null && !vc.isNull()) {
                int fmt = vc.pix_fmt();
                int mapped = mapDeprecatedPixFmt(fmt);
                if (mapped != fmt) {
                    vc.pix_fmt(mapped);
                    vc.color_range(avutil.AVCOL_RANGE_JPEG);
                } else if (vc.color_range() == avutil.AVCOL_RANGE_UNSPECIFIED) {
                    vc.color_range(avutil.AVCOL_RANGE_MPEG);
                }
            }

            Field picField = FFmpegFrameGrabber.class.getDeclaredField("picture");
            picField.setAccessible(true);
            AVFrame pic = (AVFrame) picField.get(grabber);
            if (pic != null && !pic.isNull()) {
                int pfmt = pic.format();
                int pmapped = mapDeprecatedPixFmt(pfmt);
                if (pmapped != pfmt) {
                    pic.format(pmapped);
                    pic.color_range(avutil.AVCOL_RANGE_JPEG);
                }
            }

            Field swsField = FFmpegFrameGrabber.class.getDeclaredField("img_convert_ctx");
            swsField.setAccessible(true);
            SwsContext sws = (SwsContext) swsField.get(grabber);
            if (sws != null && !sws.isNull() && vc != null && !vc.isNull()) {
                int srcRange = (vc.color_range() == avutil.AVCOL_RANGE_JPEG) ? 1 : 0;
                int cs = (vc.height() >= 720) ? swscale.SWS_CS_ITU709 : swscale.SWS_CS_ITU601;
                org.bytedeco.javacpp.IntPointer coefs = swscale.sws_getCoefficients(cs);
                swscale.sws_setColorspaceDetails(sws, coefs, srcRange, coefs, 1, 0, 1 << 16, 1 << 16);
            }
        } catch (Throwable ignored) {
            // Se a versão interna do JavaCV mudar campos privados, prossegue com segurança
        }
    }

    private static int mapDeprecatedPixFmt(int fmt) {
        if (fmt == avutil.AV_PIX_FMT_YUVJ420P) return avutil.AV_PIX_FMT_YUV420P;
        if (fmt == avutil.AV_PIX_FMT_YUVJ422P) return avutil.AV_PIX_FMT_YUV422P;
        if (fmt == avutil.AV_PIX_FMT_YUVJ444P) return avutil.AV_PIX_FMT_YUV444P;
        if (fmt == avutil.AV_PIX_FMT_YUVJ440P) return avutil.AV_PIX_FMT_YUV440P;
        return fmt;
    }

    // ------------------------------------------------------------------ shaders

    private static final String VERT = """
            #version 330 core
            out vec2 vUV;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUV = vec2(p.x, 1.0 - p.y);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** Contrast Adaptive Sharpening (CAS) + Super-Resolução Híbrida (EASU/Anime4K + Deblock MJPEG + Micro-Contraste) + FXAA + Saturação. */
    private static final String FRAG = """
            #version 330 core
            in vec2 vUV;
            out vec4 outColor;
            uniform sampler2D uTex;
            uniform vec2  uTexel;
            uniform float uSharp;
            uniform float uRadius;
            uniform float uSaturation;
            uniform int uFxaa;
            uniform int uAiSr;
            uniform vec3 uAiParams;
            uniform vec2 uUvScale;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec2 uv = (vUV - 0.5) * uUvScale + 0.5;
                vec3 e = texture(uTex, uv).rgb;
                vec3 col = e;

                if (uAiSr == 1) {
                    vec2 tx = uTexel;
                    vec3 tl = texture(uTex, uv + vec2(-tx.x, -tx.y)).rgb;
                    vec3 tc = texture(uTex, uv + vec2( 0.0,  -tx.y)).rgb;
                    vec3 tr = texture(uTex, uv + vec2( tx.x, -tx.y)).rgb;
                    vec3 ml = texture(uTex, uv + vec2(-tx.x,  0.0 )).rgb;
                    vec3 mr = texture(uTex, uv + vec2( tx.x,  0.0 )).rgb;
                    vec3 bl = texture(uTex, uv + vec2(-tx.x,  tx.y)).rgb;
                    vec3 bc = texture(uTex, uv + vec2( 0.0,   tx.y)).rgb;
                    vec3 br = texture(uTex, uv + vec2( tx.x,  tx.y)).rgb;

                    float lTL = luma(tl), lTC = luma(tc), lTR = luma(tr);
                    float lML = luma(ml), lMC = luma(e),  lMR = luma(mr);
                    float lBL = luma(bl), lBC = luma(bc), lBR = luma(br);

                    // Gradiente Sobel para reconstrução direcional de sub-pixel (estilo FSR / Anime4K)
                    float gx = (lTR + 2.0 * lMR + lBR) - (lTL + 2.0 * lML + lBL);
                    float gy = (lBL + 2.0 * lBC + lBR) - (lTL + 2.0 * lTC + lTR);
                    float gradMag = sqrt(gx * gx + gy * gy);
                    vec2 dir = (gradMag > 1e-4) ? vec2(gx, gy) / gradMag : vec2(0.0);

                    // Amostragem sub-pixel guiada por borda para afinar contornos e textos borrados
                    float edgeFactor = smoothstep(0.025, 0.22, gradMag);
                    vec3 pushed = texture(uTex, uv + dir * tx * (0.36 * edgeFactor)).rgb;

                    // Deblocking bilateral em áreas planas (remove ruído de mosquito MJPEG 8x8)
                    vec3 crossAvg = (tc + bc + ml + mr) * 0.25;
                    vec3 diagAvg  = (tl + tr + bl + br) * 0.25;
                    vec3 ringAvg  = crossAvg * 0.65 + diagAvg * 0.35;
                    float flatMask = (1.0 - smoothstep(0.012, 0.065, gradMag)) * clamp(uAiParams.y, 0.25, 0.85);
                    vec3 baseCol = mix(mix(e, pushed, 0.42 * edgeFactor), ringAvg, flatMask * 0.45);

                    // Reconstrução de alta frequência (Clarity / Micro-contraste + RCAS anti-ringing)
                    vec3 highFreq = baseCol - ringAvg;
                    float aiGain = clamp(uAiParams.x, 0.80, 1.45) * (0.75 + 0.55 * edgeFactor);
                    vec3 srCol = baseCol + highFreq * aiGain;

                    // Clamp anti-ringing estrito na vizinhança 3x3 para evitar halos brancos
                    vec3 mn = min(min(min(tc, bc), min(ml, mr)), e);
                    vec3 mx = max(max(max(tc, bc), max(ml, mr)), e);
                    vec3 mnDiag = min(min(tl, tr), min(bl, br));
                    vec3 mxDiag = max(max(tl, tr), max(bl, br));
                    mn = min(mn, mnDiag);
                    mx = max(mx, mxDiag);
                    col = clamp(srCol, mn - 0.015, mx + 0.015);

                    // Micro-contraste adaptativo guiado pela rede neural
                    float contrast = clamp(uAiParams.z, 1.0, 1.15);
                    col = clamp((col - 0.5) * contrast + 0.5, 0.0, 1.0);
                }

                if (uSharp > 0.0) {
                    vec2 o = uTexel * uRadius;
                    vec3 b = texture(uTex, uv + vec2(0.0, -o.y)).rgb;
                    vec3 d = texture(uTex, uv + vec2(-o.x, 0.0)).rgb;
                    vec3 f = texture(uTex, uv + vec2( o.x, 0.0)).rgb;
                    vec3 h = texture(uTex, uv + vec2(0.0,  o.y)).rgb;

                    vec3 mn = min(min(min(d, col), min(f, b)), h);
                    vec3 mx = max(max(max(d, col), max(f, b)), h);

                    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
                    float effSharp = (uAiSr == 1) ? uSharp * 0.65 : uSharp;
                    float peak = -1.0 / mix(8.0, 5.0, effSharp);
                    vec3 w = amp * peak;
                    vec3 rcpW = 1.0 / (1.0 + 4.0 * w);

                    col = (b * w + d * w + f * w + h * w + col) * rcpW;
                }

                if (uFxaa == 1) {
                    vec3 n = texture(uTex, uv + vec2(0.0, -uTexel.y)).rgb;
                    vec3 s = texture(uTex, uv + vec2(0.0, uTexel.y)).rgb;
                    vec3 wv = texture(uTex, uv + vec2(-uTexel.x, 0.0)).rgb;
                    vec3 ev = texture(uTex, uv + vec2(uTexel.x, 0.0)).rgb;
                    float edge = max(
                        max(abs(dot(n - e, vec3(0.299, 0.587, 0.114))), abs(dot(s - e, vec3(0.299, 0.587, 0.114)))),
                        max(abs(dot(wv - e, vec3(0.299, 0.587, 0.114))), abs(dot(ev - e, vec3(0.299, 0.587, 0.114))))
                    );
                    if (edge > 0.035) col = mix(col, (n + s + wv + ev) * 0.25, 0.25);
                }

                float finalLuma = dot(col, vec3(0.2126, 0.7152, 0.0722));
                float effSat = (uAiSr == 1) ? uSaturation * 1.06 : uSaturation;
                col = mix(vec3(finalLuma), col, effSat);
                outColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
            """;

    private static final String UI_VERT = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;
            uniform vec2 uScreen;
            out vec2 vUV;
            out vec4 vColor;
            void main() {
                vUV = aUV;
                vColor = aColor;
                vec2 ndc = vec2((aPos.x / uScreen.x) * 2.0 - 1.0, 1.0 - (aPos.y / uScreen.y) * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    private static final String UI_FRAG = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            uniform sampler2D uFontTex;
            out vec4 outColor;
            void main() {
                if (vUV.x < 0.0) {
                    outColor = vColor;
                } else {
                    float a = texture(uFontTex, vUV).r;
                    outColor = vec4(vColor.rgb, vColor.a * a);
                }
            }
            """;

    // ------------------------------------------------------------------ estado compartilhado

    private static final int MENU_BAR_HEIGHT = 28;

    // Estado mutável em tempo real sincronizado entre Launcher, Captura e Monitor
    private volatile String deviceName;
    private volatile String audioDeviceName;
    private volatile String outputMixerName;
    private volatile int captureWidth;
    private volatile int captureHeight;
    private volatile boolean autoResolution;
    private volatile int captureFps;
    private volatile boolean captureMjpeg;
    private volatile boolean sharpOn = true;
    private volatile float sharp;
    private volatile float radius;
    private volatile float saturationLevel;
    private volatile boolean fxaaEnabled;
    private volatile String scaleMode;
    private volatile boolean aiRequested;
    private volatile String gpuPreference;
    private volatile String qualityProfile;
    private volatile boolean debugEnabled;
    private volatile boolean vsyncEnabled = true;

    private volatile boolean muted = false;
    private volatile float volume = 1.0f;

    // Controle de ciclo de vida e sincronização
    private volatile boolean running = true;
    private volatile boolean returnToLauncherRequested = false;
    private final AtomicBoolean captureRestartRequested = new AtomicBoolean(false);
    private final AtomicBoolean audioRestartRequested = new AtomicBoolean(false);
    private final AtomicBoolean aiReinitRequested = new AtomicBoolean(false);
    private volatile FFmpegFrameGrabber currentGrabber = null;

    private final AtomicReference<FrameExchange> exchangeRef = new AtomicReference<>();
    private final List<FrameExchange> retiredExchanges = new CopyOnWriteArrayList<>();
    private volatile AiSuperResolution aiProcessor;

    // Status para diagnóstico visual e OSD
    private volatile String captureStatusText = "Inicializando captura...";
    private volatile boolean captureHasError = false;
    private volatile int actualInputWidth = 0;
    private volatile int actualInputHeight = 0;
    private volatile double measuredCaptureFps = 0.0;
    private volatile String aiStatusSummary = "Desativada";
    private volatile String toastMessage = "";
    private volatile long toastExpireNano = 0L;

    // Lista de mixers e dispositivos conhecidos
    private final List<String> availableOutputMixers = new ArrayList<>();
    private final List<String> knownVideoDevices = new ArrayList<>(List.of("USB Video", "Cam Link 4K", "OBS Virtual Camera", "desktop"));
    private final List<String> knownAudioDevices = new ArrayList<>(List.of(
            "Digital Audio Interface (USB Digital Audio)",
            "Interface de áudio digital (USB Audio Device)",
            "Microfone (USB Audio Device)",
            "(Sem Áudio)"
    ));

    // Estado da janela e UI OpenGL (somente thread principal)
    private long window;
    private int winW = 1280, winH = 720;
    private int fbW = 1280, fbH = 720;
    private boolean fullscreen = false;
    private int windowedX = 100, windowedY = 100, windowedW = 1280, windowedH = 720;

    // Estado de mouse e menus na janela de monitor
    private double mouseX = 0, mouseY = 0;
    private boolean mouseDown = false;
    private int openMenuIndex = -1; // -1 = nenhum, 0=Arquivo, 1=Vídeo, 2=Filtros, 3=Áudio, 4=Opções
    private boolean showFilterPanel = false;
    private boolean showOptionsPanel = false;
    private boolean showShortcutsModal = false;
    private String activeSliderId = null;

    // Panels draggable positions
    private float filterPanelX = 24f, filterPanelY = 42f;
    private float optionsPanelX = 390f, optionsPanelY = 42f;
    private boolean draggingFilterPanel = false, draggingOptionsPanel = false;
    private float dragOffsetX = 0f, dragOffsetY = 0f;

    public SwitchPlayerApp(Config cfg) {
        applyConfigToState(cfg);
        refreshAudioMixers();
    }

    private void applyConfigToState(Config cfg) {
        this.deviceName = cfg.device();
        this.audioDeviceName = cfg.audioDevice();
        this.outputMixerName = cfg.outputMixer();
        this.captureWidth = cfg.width();
        this.captureHeight = cfg.height();
        this.autoResolution = cfg.autoResolution();
        this.captureFps = cfg.fps();
        this.captureMjpeg = cfg.mjpeg();
        this.sharp = cfg.sharp();
        this.sharpOn = cfg.sharp() > 0.001f;
        this.radius = cfg.radius();
        this.saturationLevel = cfg.saturation();
        this.fxaaEnabled = cfg.fxaa();
        this.scaleMode = cfg.scaleMode();
        this.aiRequested = cfg.aiEnabled();
        this.gpuPreference = cfg.gpuBackend();
        this.qualityProfile = cfg.qualityProfile() == null ? "Equilibrado" : cfg.qualityProfile();
        this.debugEnabled = cfg.debug();
        updateAiStatusSummary();
    }

    public Config snapshotConfig() {
        return new Config(
                deviceName, audioDeviceName, outputMixerName,
                captureWidth, captureHeight, autoResolution,
                captureFps, captureMjpeg,
                sharpOn ? sharp : 0f, radius, saturationLevel,
                fxaaEnabled, scaleMode, aiRequested, gpuPreference, qualityProfile,
                false, false, debugEnabled
        );
    }

    private void refreshAudioMixers() {
        availableOutputMixers.clear();
        availableOutputMixers.add("(Padrão do Sistema)");
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                for (Line.Info lineInfo : mixer.getSourceLineInfo()) {
                    if (lineInfo instanceof DataLine.Info di && SourceDataLine.class.isAssignableFrom(di.getLineClass())) {
                        availableOutputMixers.add(info.getName());
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private void showToast(String msg) {
        this.toastMessage = msg;
        this.toastExpireNano = System.nanoTime() + 3_200_000_000L;
        System.out.println("[monitor] " + msg);
    }

    private void updateAiStatusSummary() {
        if (!aiRequested) {
            aiStatusSummary = AiSuperResolution.isModelPresent()
                    ? "Desativada (Modelo ONNX pronto)"
                    : "Desativada (Modelo ONNX ausente em models/)";
            return;
        }
        AiSuperResolution proc = this.aiProcessor;
        if (proc != null && !proc.isClosed()) {
            aiStatusSummary = "Ativa: " + proc.backendName()
                    + (proc.lastInferenceMs() > 0 ? " (" + proc.lastInferenceMs() + " ms)" : "");
        } else if (!AiSuperResolution.isModelPresent()) {
            aiStatusSummary = "Indisponível: coloque " + AiSuperResolution.DEFAULT_MODEL_PATH;
        } else {
            aiStatusSummary = "Inicializando ONNX (" + gpuPreference + ")...";
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        redirectLogToFile();
        installFfmpegLogFilter();
        Config cfg = Config.parse(args);
        if (cfg.list()) {
            runFfmpeg("-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
            return;
        }
        if (cfg.modes()) {
            runFfmpeg("-hide_banner", "-list_options", "true", "-f", "dshow", "-i", cfg.dshowInput());
            return;
        }

        boolean useLauncher = (args.length == 0);
        Config currentCfg = cfg;

        while (true) {
            if (useLauncher) {
                currentCfg = showLauncher(currentCfg);
                if (currentCfg == null) return;
            }
            SwitchPlayerApp app = new SwitchPlayerApp(currentCfg);
            boolean backToLauncher = app.run();
            currentCfg = app.snapshotConfig();
            if (backToLauncher) {
                useLauncher = true;
            } else {
                break;
            }
        }
    }

    /** Launcher Swing nativo preservado e sincronizado com o estado do Monitor. */
    private static Config showLauncher(Config initial) throws Exception {
        final Config[] result = new Config[1];
        Runnable ui = () -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            JFrame frame = new JFrame("Mvisual — Launcher Alpha");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            try (InputStream in = SwitchPlayerApp.class.getResourceAsStream("/icon.png")) {
                if (in != null) frame.setIconImage(ImageIO.read(in));
            } catch (Exception ignored) { }
            frame.setSize(620, 680);
            frame.setResizable(true);
            frame.setLocationRelativeTo(null);
            JPanel root = new JPanel(new GridBagLayout());
            root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(5, 0, 5, 0);
            JLabel title = new JLabel("Mvisual Alpha v1.1.0");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            root.add(title, c);
            c.gridy++;
            root.add(new JLabel("Selecione a captura, filtros iniciais e saída de áudio antes de iniciar."), c);

            JComboBox<String> video = new JComboBox<>(new String[]{"USB Video", "Cam Link 4K", "OBS Virtual Camera", "desktop"});
            video.setEditable(true);
            if (initial != null && initial.device() != null) video.setSelectedItem(initial.device());

            JComboBox<String> audio = new JComboBox<>(new String[]{
                    "Digital Audio Interface (USB Digital Audio)",
                    "Interface de áudio digital (USB Audio Device)",
                    "Microfone (USB Audio Device)",
                    "(Sem Áudio)"
            });
            audio.setEditable(true);
            if (initial != null && initial.audioDevice() != null && !initial.audioDevice().isBlank()) {
                audio.setSelectedItem(initial.audioDevice());
            } else {
                audio.setSelectedIndex(0);
            }

            List<String> mixerLabels = new ArrayList<>();
            mixerLabels.add("(Padrão do Sistema)");
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                boolean supports = false;
                for (Line.Info lineInfo : mixer.getSourceLineInfo()) {
                    if (lineInfo instanceof DataLine.Info di && SourceDataLine.class.isAssignableFrom(di.getLineClass())) {
                        supports = true;
                        break;
                    }
                }
                if (supports) mixerLabels.add(info.getName());
            }
            JComboBox<String> output = new JComboBox<>(mixerLabels.toArray(String[]::new));
            output.setEditable(false);
            if (initial != null && initial.outputMixer() != null && !initial.outputMixer().isBlank()) {
                output.setSelectedItem(initial.outputMixer());
            }

            LinkedHashSet<String> resolutionChoices = new LinkedHashSet<>();
            resolutionChoices.add("Automática (nativa da placa / 1080p)");
            resolutionChoices.add("1920x1080");
            resolutionChoices.add("1600x1200");
            resolutionChoices.add("1360x768");
            resolutionChoices.add("1280x1024");
            resolutionChoices.add("1280x960");
            resolutionChoices.add("1280x720");
            resolutionChoices.add("1024x768");
            resolutionChoices.add("800x600");
            resolutionChoices.add("720x576");
            resolutionChoices.add("720x480");
            resolutionChoices.add("640x480");
            try {
                GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                for (DisplayMode dm : gd.getDisplayModes()) {
                    if (dm.getWidth() >= 640 && dm.getHeight() >= 480) {
                        resolutionChoices.add(dm.getWidth() + "x" + dm.getHeight());
                    }
                }
            } catch (Exception ignored) { }
            JComboBox<String> resolution = new JComboBox<>(resolutionChoices.toArray(String[]::new));
            if (initial != null) {
                if (initial.autoResolution()) {
                    resolution.setSelectedIndex(0);
                } else {
                    String wh = initial.width() + "x" + initial.height();
                    resolution.setSelectedItem(wh);
                }
            }

            JComboBox<String> gpu = new JComboBox<>(new String[]{"Automático (recomendado)", "AMD Radeon", "NVIDIA GeForce / RTX", "Intel / Outra"});
            if (initial != null && initial.gpuBackend() != null) gpu.setSelectedItem(initial.gpuBackend());

            JComboBox<String> profile = new JComboBox<>(new String[]{"Baixa latência", "Equilibrado", "Qualidade (mais filtros)"});
            if (initial != null && initial.qualityProfile() != null) profile.setSelectedItem(initial.qualityProfile());

            addLauncherRow(root, c, "Preferência de GPU", gpu);
            addLauncherRow(root, c, "Perfil gráfico", profile);

            JComboBox<String> rate = new JComboBox<>(new String[]{"60 FPS MJPEG", "60 FPS YUY2", "30 FPS"});
            if (initial != null) {
                if (initial.fps() == 30) rate.setSelectedItem("30 FPS");
                else if (initial.mjpeg()) rate.setSelectedItem("60 FPS MJPEG");
                else rate.setSelectedItem("60 FPS YUY2");
            }

            addLauncherRow(root, c, "Fonte de vídeo (DirectShow/GDI)", video);
            addLauncherRow(root, c, "Entrada de áudio da captura", audio);
            addLauncherRow(root, c, "Saída de áudio do Windows", output);
            addLauncherRow(root, c, "Resolução de captura solicitada", resolution);

            JComboBox<String> scaleMode = new JComboBox<>(new String[]{"Preservar proporção", "Preencher (cortar bordas)", "Esticar para preencher"});
            if (initial != null && initial.scaleMode() != null) scaleMode.setSelectedItem(initial.scaleMode());
            addLauncherRow(root, c, "Escala da imagem", scaleMode);

            int initSat = initial != null ? Math.round(initial.saturation() * 100f) : 110;
            JSlider saturation = new JSlider(0, 200, Math.max(0, Math.min(200, initSat)));
            saturation.setMajorTickSpacing(50);
            saturation.setPaintTicks(true);
            saturation.setPaintLabels(true);
            addLauncherRow(root, c, "Saturação de cor (%)", saturation);

            JCheckBox fxaa = new JCheckBox("Suavização de serrilhado (FXAA)", initial != null && initial.fxaa());
            c.gridy++; c.insets = new Insets(4, 0, 4, 0); root.add(fxaa, c);

            String aiLabel = "Super-resolução por IA (experimental — "
                    + (AiSuperResolution.isModelPresent() ? "modelo detectado)" : "requer models/realesrgan-x2plus.onnx)");
            JCheckBox ai = new JCheckBox(aiLabel, initial != null && initial.aiEnabled());
            c.gridy++; c.insets = new Insets(4, 0, 4, 0); root.add(ai, c);

            addLauncherRow(root, c, "Formato / taxa de quadros", rate);

            JButton start = new JButton("Iniciar Monitor Mvisual");
            start.setFont(start.getFont().deriveFont(Font.BOLD, 14f));
            c.gridy++; c.insets = new Insets(14, 0, 0, 0); root.add(start, c);

            JLabel note = new JLabel("Na janela do monitor use a barra superior ou atalhos (F11 tela cheia, S nitidez, M mudo).");
            note.setFont(note.getFont().deriveFont(10f));
            c.gridy++; c.insets = new Insets(9, 0, 0, 0); root.add(note, c);

            start.addActionListener(e -> {
                String v = String.valueOf(video.getEditor().getItem()).trim();
                String a = String.valueOf(audio.getEditor().getItem()).trim();
                String r = String.valueOf(resolution.getSelectedItem());
                int w, h;
                boolean autoRes = false;
                if (r.startsWith("Automática")) {
                    Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                    w = d.width;
                    h = d.height;
                    autoRes = true;
                } else {
                    String[] wh = r.split("x");
                    w = Integer.parseInt(wh[0]);
                    h = Integer.parseInt(wh[1]);
                }
                String prof = String.valueOf(profile.getSelectedItem());
                float initSharp = initial != null ? initial.sharp() : 0.6f;
                boolean useFxaa = fxaa.isSelected();
                if ("Baixa latência".equals(prof) && initial == null) {
                    initSharp = 0.4f;
                } else if ("Qualidade (mais filtros)".equals(prof) && initial == null) {
                    initSharp = 0.75f;
                    useFxaa = true;
                }
                String fpsChoice = String.valueOf(rate.getSelectedItem());
                int fps = fpsChoice.startsWith("30") ? 30 : 60;
                boolean mjpeg = fpsChoice.contains("MJPEG");
                String mixerName = output.getSelectedItem() == null ? "" : output.getSelectedItem().toString();
                if ("(Padrão do Sistema)".equals(mixerName)) mixerName = "";
                result[0] = new Config(
                        v, a, mixerName, w, h, autoRes, fps, mjpeg,
                        initSharp, 1.0f, saturation.getValue() / 100.0f,
                        useFxaa, String.valueOf(scaleMode.getSelectedItem()),
                        ai.isSelected(), String.valueOf(gpu.getSelectedItem()), prof,
                        false, false, initial != null && initial.debug()
                );
                frame.dispose();
            });
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    synchronized (result) { result.notifyAll(); }
                }
            });
            frame.setContentPane(root);
            frame.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) ui.run();
        else SwingUtilities.invokeAndWait(ui);

        while (result[0] == null && java.awt.Frame.getFrames().length > 0) {
            boolean visible = false;
            for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                if (f.isVisible() && f.getTitle().startsWith("Mvisual — Launcher")) visible = true;
            }
            if (!visible) break;
            Thread.sleep(80);
        }
        return result[0];
    }

    private static void addLauncherRow(JPanel root, GridBagConstraints c, String label, JComponent input) {
        c.gridy++; c.insets = new Insets(5, 0, 0, 0); root.add(new JLabel(label), c);
        c.gridy++; c.insets = new Insets(2, 0, 4, 0); root.add(input, c);
    }

    private static void runFfmpeg(String... args) throws Exception {
        String exe = Loader.load(ffmpeg.class);
        String[] cmd = new String[args.length + 1];
        cmd[0] = exe;
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        p.waitFor();
    }

    private static void redirectLogToFile() {
        if (!"file".equals(System.getProperty("mvisual.log"))) return;
        try {
            String base = System.getenv("LOCALAPPDATA");
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    base != null ? base : System.getProperty("user.home"), "Mvisual");
            java.nio.file.Files.createDirectories(dir);
            java.io.PrintStream ps = new java.io.PrintStream(
                    new java.io.FileOutputStream(dir.resolve("mvisual.log").toFile(), false), true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception ignored) { }
    }

    public boolean run() {
        Thread capture = new Thread(this::captureLoop, "capture");
        capture.setDaemon(true);
        capture.setPriority(Thread.MAX_PRIORITY);
        capture.start();

        try {
            renderLoop();
        } finally {
            running = false;
            closeAiSafely();
            try {
                capture.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            FrameExchange ex = exchangeRef.getAndSet(null);
            if (ex != null) ex.free();
            for (FrameExchange old : retiredExchanges) {
                old.free();
            }
            retiredExchanges.clear();
        }
        return returnToLauncherRequested;
    }

    /** Solicita o reinício limpo e seguro da captura sem fechar a janela do monitor. */
    public void requestCaptureRestart(String reason) {
        captureStatusText = "Reiniciando captura (" + reason + ")...";
        captureHasError = false;
        captureRestartRequested.set(true);
        showToast("Reiniciando captura: " + reason);
    }

    private void closeAiSafely() {
        AiSuperResolution proc = this.aiProcessor;
        this.aiProcessor = null;
        if (proc != null) {
            try { proc.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------- THREAD 1: captura

    private FFmpegFrameGrabber openGrabber(boolean withAudio, int reqW, int reqH) {
        String dev = this.deviceName;
        String aud = this.audioDeviceName;
        boolean desktop = dev.equalsIgnoreCase("desktop");
        String input;
        if (desktop) {
            input = "desktop";
        } else {
            String video = dev.startsWith("video=") ? dev.substring(6) : dev;
            boolean audioEnabled = withAudio && aud != null && !aud.isBlank() && !aud.equals("(Sem Áudio)");
            input = "video=" + video;
            if (audioEnabled) {
                String audio = aud.startsWith("audio=") ? aud.substring(6) : aud;
                input += ":audio=" + audio;
            }
        }
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(input);
        g.setFormat(desktop ? "gdigrab" : "dshow");
        if (reqW > 0 && reqH > 0) {
            g.setImageWidth(reqW);
            g.setImageHeight(reqH);
        }
        g.setFrameRate(this.captureFps);
        g.setImageScalingFlags(swscale.SWS_BILINEAR);
        if (desktop) {
            g.setOption("framerate", Integer.toString(this.captureFps));
            g.setOption("draw_mouse", "1");
        } else if (this.captureMjpeg) {
            g.setOption("vcodec", "mjpeg");
        }
        g.setOption("fflags", "nobuffer");
        g.setOption("flags", "low_delay");
        g.setOption("audio_buffer_size", "20");
        g.setOption("probesize", "32");
        g.setOption("analyzeduration", "0");
        g.setOption("rtbufsize", "64M");
        return g;
    }

    private SourceDataLine openAudioLine(FFmpegFrameGrabber grabber) throws Exception {
        int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48000;
        int channels = grabber.getAudioChannels() > 0 ? Math.min(2, grabber.getAudioChannels()) : 2;
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        Mixer.Info chosen = null;
        String targetMixer = this.outputMixerName;
        if (targetMixer != null && !targetMixer.isBlank() && !targetMixer.equals("(Padrão do Sistema)")) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(targetMixer)) {
                    chosen = mi;
                    break;
                }
            }
        }
        SourceDataLine line;
        if (chosen != null) {
            Mixer mixer = AudioSystem.getMixer(chosen);
            line = (SourceDataLine) mixer.getLine(info);
        } else {
            line = (SourceDataLine) AudioSystem.getLine(info);
        }
        line.open(format, Math.max(4096, sampleRate * channels / 5));
        line.start();
        return line;
    }

    private void playAudioFrame(Frame frame, SourceDataLine line) {
        if (frame.samples == null || line == null || muted) return;
        float vol = this.volume;
        for (Buffer sample : frame.samples) {
            if (sample instanceof ShortBuffer shorts) {
                ShortBuffer copy = shorts.duplicate();
                byte[] bytes = new byte[copy.remaining() * 2];
                int i = 0;
                while (copy.hasRemaining()) {
                    int value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(copy.get() * vol)));
                    bytes[i++] = (byte) (value & 0xff);
                    bytes[i++] = (byte) ((value >>> 8) & 0xff);
                }
                line.write(bytes, 0, bytes.length);
            } else if (sample instanceof ByteBuffer bb) {
                ByteBuffer copy = bb.duplicate();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                if (vol < 0.999f || vol > 1.001f) {
                    for (int i = 0; i + 1 < bytes.length; i += 2) {
                        short v = (short) ((bytes[i + 1] << 8) | (bytes[i] & 0xff));
                        int scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * vol)));
                        bytes[i] = (byte) scaled;
                        bytes[i + 1] = (byte) (scaled >>> 8);
                    }
                }
                line.write(bytes, 0, bytes.length);
            }
        }
    }

    private FFmpegFrameGrabber startGrabberWithFallback(boolean wantAudio) throws Exception {
        int[][] candidates = this.autoResolution
                ? new int[][]{{1920, 1080}, {1280, 720}, {0, 0}}
                : new int[][]{{this.captureWidth, this.captureHeight}, {1920, 1080}, {1280, 720}, {0, 0}};

        Exception lastErr = null;
        for (int[] res : candidates) {
            int rw = res[0], rh = res[1];
            try {
                FFmpegFrameGrabber g = openGrabber(wantAudio, rw, rh);
                g.start();
                return g;
            } catch (Exception ex) {
                lastErr = ex;
                if (wantAudio) {
                    try {
                        FFmpegFrameGrabber g = openGrabber(false, rw, rh);
                        g.start();
                        System.err.println("[capture] Entrada combinada falhou; aberto somente vídeo em " + (rw == 0 ? "nativo" : rw + "x" + rh));
                        return g;
                    } catch (Exception ex2) {
                        lastErr = ex2;
                    }
                }
            }
        }
        throw lastErr != null ? lastErr : new IllegalStateException("Não foi possível abrir o dispositivo de captura.");
    }

    private void syncAiProcessorState() {
        if (!aiRequested) {
            if (aiProcessor != null) {
                closeAiSafely();
                updateAiStatusSummary();
            }
            return;
        }
        if (aiReinitRequested.getAndSet(false)) {
            closeAiSafely();
        }
        if (aiProcessor != null && aiProcessor.isClosed()) {
            String err = aiProcessor.lastError();
            closeAiSafely();
            aiRequested = false;
            aiStatusSummary = "Desativada após erro: " + (err != null ? err : "falha no modelo");
            return;
        }
        if (aiProcessor == null) {
            if (!AiSuperResolution.isModelPresent()) {
                aiRequested = false;
                aiStatusSummary = "Modelo ausente: " + AiSuperResolution.DEFAULT_MODEL_PATH;
                showToast("IA indisponível: coloque " + AiSuperResolution.DEFAULT_MODEL_PATH);
                return;
            }
            try {
                aiProcessor = AiSuperResolution.open(AiSuperResolution.DEFAULT_MODEL_PATH, gpuPreference);
                updateAiStatusSummary();
                showToast("IA ativa: " + aiProcessor.backendName());
            } catch (Exception aiError) {
                aiRequested = false;
                aiStatusSummary = "Erro IA: " + aiError.getMessage();
                showToast("Falha ao iniciar IA: " + aiError.getMessage());
            }
        } else {
            updateAiStatusSummary();
        }
    }

    private void captureLoop() {
        long frames = 0, t0 = System.nanoTime();
        while (running) {
            captureRestartRequested.set(false);
            FFmpegFrameGrabber g = null;
            SourceDataLine line = null;
            boolean wantAudio = audioDeviceName != null
                    && !audioDeviceName.isBlank()
                    && !audioDeviceName.equals("(Sem Áudio)")
                    && !deviceName.equalsIgnoreCase("desktop");
            try {
                captureStatusText = "Conectando a '" + deviceName + "'...";
                captureHasError = false;
                g = startGrabberWithFallback(wantAudio);
                currentGrabber = g;
                normalizeGrabberColorRange(g);

                if (wantAudio && g.getAudioChannels() > 0) {
                    try {
                        line = openAudioLine(g);
                    } catch (Exception audioOpenError) {
                        System.err.println("[audio] Saída indisponível: " + audioOpenError.getMessage());
                    }
                }

                actualInputWidth = g.getImageWidth();
                actualInputHeight = g.getImageHeight();
                captureStatusText = "Ativo: " + deviceName + " (" + actualInputWidth + "x" + actualInputHeight + " @ " + Math.round(g.getFrameRate()) + " FPS)";
                System.out.println("[capture] Aberto: " + actualInputWidth + "x" + actualInputHeight + " @ " + g.getFrameRate() + " fps");

                FrameExchange ex = exchangeRef.get();
                boolean colorRangeNormalizedAfterFirstFrame = false;

                while (running && !captureRestartRequested.get()) {
                    // Verifica se o usuário trocou a saída de áudio no menu sem interromper o vídeo
                    if (audioRestartRequested.getAndSet(false)) {
                        if (line != null) {
                            try { line.stop(); } catch (Exception ignored) { }
                            try { line.close(); } catch (Exception ignored) { }
                            line = null;
                        }
                        if (g.getAudioChannels() > 0) {
                            try {
                                line = openAudioLine(g);
                                showToast("Saída de áudio alterada: " + (outputMixerName.isBlank() ? "Padrão" : outputMixerName));
                            } catch (Exception audioErr) {
                                showToast("Erro ao abrir saída de áudio: " + audioErr.getMessage());
                            }
                        }
                    }

                    syncAiProcessorState();

                    Frame f = g.grab();
                    if (f == null) {
                        captureStatusText = "Sinal interrompido em '" + deviceName + "'. Reconectando...";
                        captureHasError = true;
                        break;
                    }
                    if (!colorRangeNormalizedAfterFirstFrame && f.image != null) {
                        normalizeGrabberColorRange(g);
                        colorRangeNormalizedAfterFirstFrame = true;
                    }
                    if (f.samples != null) {
                        playAudioFrame(f, line);
                    }
                    if (f.image == null || f.image.length == 0) continue;

                    actualInputWidth = f.imageWidth;
                    actualInputHeight = f.imageHeight;
                    ByteBuffer src = (ByteBuffer) f.image[0];
                    int outW = f.imageWidth, outH = f.imageHeight;

                    AiSuperResolution proc = this.aiProcessor;
                    if (proc != null && !proc.isClosed()) {
                        proc.submitAsync(src, f.imageWidth, f.imageHeight, f.imageStride);
                        updateAiStatusSummary();
                    }

                    if (ex == null || ex.width != outW || ex.height != outH) {
                        FrameExchange nextEx = new FrameExchange(outW, outH);
                        FrameExchange prevEx = exchangeRef.getAndSet(nextEx);
                        if (prevEx != null) retiredExchanges.add(prevEx);
                        ex = nextEx;
                    }

                    long srcAddress = MemoryUtil.memAddress(src);
                    long dstAddress = MemoryUtil.memAddress(ex.writeBuffer());
                    int row = f.imageWidth * 3, stride = f.imageStride;
                    if (stride == row) {
                        MemoryUtil.memCopy(srcAddress, dstAddress, ex.bytes);
                    } else {
                        for (int y = 0; y < outH; y++) {
                            MemoryUtil.memCopy(srcAddress + (long) y * stride, dstAddress + (long) y * row, row);
                        }
                    }
                    ex.publish();

                    frames++;
                    long now = System.nanoTime();
                    if (now - t0 >= 1_000_000_000L) {
                        measuredCaptureFps = frames * 1e9 / (now - t0);
                        if (debugEnabled) {
                            System.out.printf("[capture] %.1f fps%n", measuredCaptureFps);
                        }
                        frames = 0;
                        t0 = now;
                    }
                }
            } catch (Exception e) {
                captureHasError = true;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                captureStatusText = "Sem sinal / Falha ao abrir '" + deviceName + "': " + msg;
                System.err.println("[capture] Erro: " + msg);
            } finally {
                currentGrabber = null;
                if (line != null) {
                    try { line.stop(); } catch (Exception ignored) { }
                    try { line.close(); } catch (Exception ignored) { }
                }
                if (g != null) {
                    try { g.stop(); } catch (Exception ignored) { }
                    try { g.release(); } catch (Exception ignored) { }
                }
            }

            if (running && !captureRestartRequested.get()) {
                for (int i = 0; i < 10 && running && !captureRestartRequested.get(); i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void toggleFullscreen(long w) {
        if (!fullscreen) {
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer x = st.mallocInt(1), y = st.mallocInt(1), width = st.mallocInt(1), height = st.mallocInt(1);
                glfwGetWindowPos(w, x, y);
                glfwGetWindowSize(w, width, height);
                windowedX = x.get(0);
                windowedY = y.get(0);
                windowedW = width.get(0);
                windowedH = height.get(0);
            }
            long monitor = glfwGetPrimaryMonitor();
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer x = st.mallocInt(1), y = st.mallocInt(1);
                glfwGetMonitorPos(monitor, x, y);
                glfwSetWindowAttrib(w, GLFW_DECORATED, GLFW_FALSE);
                glfwSetWindowPos(w, x.get(0), y.get(0));
                glfwSetWindowSize(w, mode.width(), mode.height());
            }
            fullscreen = true;
            openMenuIndex = -1;
            showToast("Tela cheia ativada (F11 para voltar ao modo janela)");
        } else {
            glfwSetWindowAttrib(w, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowPos(w, windowedX, windowedY);
            glfwSetWindowSize(w, Math.max(640, windowedW), Math.max(360, windowedH));
            fullscreen = false;
            showToast("Modo janela restaurado");
        }
    }

    private void applyWindowIcon() {
        try (InputStream in = SwitchPlayerApp.class.getResourceAsStream("/icon.png")) {
            if (in == null) return;
            BufferedImage image = ImageIO.read(in);
            int width = image.getWidth(), height = image.getHeight();
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = image.getRGB(x, y);
                        pixels.put((byte) ((argb >> 16) & 255))
                                .put((byte) ((argb >> 8) & 255))
                                .put((byte) (argb & 255))
                                .put((byte) ((argb >> 24) & 255));
                    }
                }
                pixels.flip();
                try (MemoryStack st = MemoryStack.stackPush()) {
                    GLFWImage.Buffer icons = GLFWImage.mallocStack(1, st);
                    icons.position(0).width(width).height(height).pixels(pixels);
                    glfwSetWindowIcon(window, icons);
                }
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Exception e) {
            System.err.println("[icon] " + e.getMessage());
        }
    }

    // --------------------------------------------------- UI OpenGL Renderer (Menus & Panels)

    static final class GlUiRenderer {
        private static final int FIRST_CHAR = 32;
        private static final int LAST_CHAR = 255;
        private static final int CHAR_COUNT = LAST_CHAR - FIRST_CHAR + 1;
        private static final int ATLAS_W = 512;
        private static final int ATLAS_H = 512;

        final int program;
        final int uScreen;
        final int uFontTex;
        final int vao;
        final int vbo;
        final int fontTex;

        final float[] charU0 = new float[CHAR_COUNT];
        final float[] charV0 = new float[CHAR_COUNT];
        final float[] charU1 = new float[CHAR_COUNT];
        final float[] charV1 = new float[CHAR_COUNT];
        final float[] charW = new float[CHAR_COUNT];
        final float[] charAdv = new float[CHAR_COUNT];
        float lineHeight = 18f;

        private final FloatBuffer vertexBuffer;
        private int vertexCount = 0;
        private static final int MAX_VERTICES = 24576;

        GlUiRenderer() {
            program = buildProgram(UI_VERT, UI_FRAG);
            uScreen = glGetUniformLocation(program, "uScreen");
            uFontTex = glGetUniformLocation(program, "uFontTex");

            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * 8 * Float.BYTES, GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 8 * Float.BYTES, 0L);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 8 * Float.BYTES, 2L * Float.BYTES);
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, 8 * Float.BYTES, 4L * Float.BYTES);

            fontTex = buildFontAtlas();
            vertexBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * 8);
        }

        private int buildFontAtlas() {
            BufferedImage img = new BufferedImage(ATLAS_W, ATLAS_H, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, ATLAS_W, ATLAS_H);
            g.setColor(Color.WHITE);
            Font font = new Font("Segoe UI", Font.PLAIN, 14);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            lineHeight = fm.getHeight();
            int ascent = fm.getAscent();

            int cx = 2, cy = 2;
            int rowH = fm.getHeight() + 4;
            for (int ch = FIRST_CHAR; ch <= LAST_CHAR; ch++) {
                int idx = ch - FIRST_CHAR;
                String s = String.valueOf((char) ch);
                int cw = Math.max(1, fm.charWidth((char) ch));
                int padW = cw + 2;
                if (cx + padW + 2 >= ATLAS_W) {
                    cx = 2;
                    cy += rowH;
                }
                g.drawString(s, cx, cy + ascent);
                charU0[idx] = cx / (float) ATLAS_W;
                charV0[idx] = cy / (float) ATLAS_H;
                charU1[idx] = (cx + cw) / (float) ATLAS_W;
                charV1[idx] = (cy + fm.getHeight()) / (float) ATLAS_H;
                charW[idx] = cw;
                charAdv[idx] = cw;
                cx += padW + 2;
            }
            g.dispose();

            ByteBuffer buf = MemoryUtil.memAlloc(ATLAS_W * ATLAS_H);
            try {
                java.awt.image.Raster raster = img.getData();
                int[] px = new int[1];
                for (int y = 0; y < ATLAS_H; y++) {
                    for (int x = 0; x < ATLAS_W; x++) {
                        raster.getPixel(x, y, px);
                        buf.put((byte) (px[0] & 0xFF));
                    }
                }
                buf.flip();
                int tex = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, tex);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, ATLAS_W, ATLAS_H, 0, GL_RED, GL_UNSIGNED_BYTE, buf);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                return tex;
            } finally {
                MemoryUtil.memFree(buf);
            }
        }

        void begin() {
            vertexBuffer.clear();
            vertexCount = 0;
        }

        private void pushVertex(float x, float y, float u, float v, float r, float g, float b, float a) {
            if (vertexCount >= MAX_VERTICES) return;
            vertexBuffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
            vertexCount++;
        }

        void fillRect(float x, float y, float w, float h, float r, float g, float b, float a) {
            if (vertexCount + 6 > MAX_VERTICES) return;
            float x2 = x + w, y2 = y + h;
            pushVertex(x, y, -1f, -1f, r, g, b, a);
            pushVertex(x2, y, -1f, -1f, r, g, b, a);
            pushVertex(x2, y2, -1f, -1f, r, g, b, a);
            pushVertex(x, y, -1f, -1f, r, g, b, a);
            pushVertex(x2, y2, -1f, -1f, r, g, b, a);
            pushVertex(x, y2, -1f, -1f, r, g, b, a);
        }

        void strokeRect(float x, float y, float w, float h, float thickness, float r, float g, float b, float a) {
            fillRect(x, y, w, thickness, r, g, b, a);
            fillRect(x, y + h - thickness, w, thickness, r, g, b, a);
            fillRect(x, y, thickness, h, r, g, b, a);
            fillRect(x + w - thickness, y, thickness, h, r, g, b, a);
        }

        float measureText(String text) {
            if (text == null || text.isEmpty()) return 0f;
            float w = 0f;
            for (int i = 0; i < text.length(); i++) {
                int ch = text.charAt(i);
                if (ch < FIRST_CHAR || ch > LAST_CHAR) ch = '?';
                w += charAdv[ch - FIRST_CHAR];
            }
            return w;
        }

        void drawText(String text, float x, float y, float r, float g, float b, float a) {
            if (text == null || text.isEmpty()) return;
            float curX = Math.round(x);
            float curY = Math.round(y);
            for (int i = 0; i < text.length(); i++) {
                if (vertexCount + 6 > MAX_VERTICES) break;
                int ch = text.charAt(i);
                if (ch < FIRST_CHAR || ch > LAST_CHAR) ch = '?';
                int idx = ch - FIRST_CHAR;
                float w = charW[idx];
                float h = lineHeight;
                float u0 = charU0[idx], v0 = charV0[idx], u1 = charU1[idx], v1 = charV1[idx];
                pushVertex(curX, curY, u0, v0, r, g, b, a);
                pushVertex(curX + w, curY, u1, v0, r, g, b, a);
                pushVertex(curX + w, curY + h, u1, v1, r, g, b, a);
                pushVertex(curX, curY, u0, v0, r, g, b, a);
                pushVertex(curX + w, curY + h, u1, v1, r, g, b, a);
                pushVertex(curX, curY + h, u0, v1, r, g, b, a);
                curX += charAdv[idx];
            }
        }

        void flush(int screenW, int screenH) {
            if (vertexCount == 0) return;
            vertexBuffer.flip();
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glUseProgram(program);
            glUniform2f(uScreen, (float) Math.max(1, screenW), (float) Math.max(1, screenH));
            glUniform1i(uFontTex, 1);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, fontTex);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);
            glActiveTexture(GL_TEXTURE0);
            glDisable(GL_BLEND);
        }

        void destroy() {
            MemoryUtil.memFree(vertexBuffer);
            glDeleteTextures(fontTex);
            glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao);
            glDeleteProgram(program);
        }
    }

    // --------------------------------------------------- Menu & UI Hit-Testing / Rendering

    private record MenuAction(String label, boolean checked, boolean enabled, Runnable action) {}

    private static final String[] TOP_MENUS = {"Arquivo", "Vídeo", "Filtros", "Áudio", "Opções"};

    private List<MenuAction> buildMenuItems(int menuIndex) {
        List<MenuAction> items = new ArrayList<>();
        switch (menuIndex) {
            case 0 -> { // Arquivo
                items.add(new MenuAction("Reiniciar captura (Ctrl+R)", false, true,
                        () -> requestCaptureRestart("Menu Arquivo")));
                items.add(new MenuAction("Reconectar dispositivos", false, true, () -> {
                    refreshAudioMixers();
                    requestCaptureRestart("Reconexão de dispositivos");
                }));
                for (String dev : knownVideoDevices) {
                    boolean active = dev.equalsIgnoreCase(deviceName);
                    items.add(new MenuAction("Fonte de vídeo: " + dev, active, true, () -> {
                        if (!dev.equalsIgnoreCase(deviceName)) {
                            deviceName = dev;
                            requestCaptureRestart("Fonte " + dev);
                        }
                    }));
                }
                items.add(new MenuAction("Voltar ao Launcher...", false, true, () -> {
                    returnToLauncherRequested = true;
                    glfwSetWindowShouldClose(window, true);
                }));
                items.add(new MenuAction("Sair do Monitor (Esc)", false, true,
                        () -> glfwSetWindowShouldClose(window, true)));
            }
            case 1 -> { // Vídeo
                items.add(new MenuAction("Resolução de captura: Automática", autoResolution, true, () -> {
                    Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                    autoResolution = true;
                    captureWidth = d.width;
                    captureHeight = d.height;
                    requestCaptureRestart("Resolução Automática");
                }));
                int[][] capResList = {{1920, 1080}, {1600, 1200}, {1360, 768}, {1280, 1024}, {1280, 960}, {1280, 720}, {1024, 768}, {800, 600}, {720, 576}, {720, 480}, {640, 480}};
                for (int[] wh : capResList) {
                    int w = wh[0], h = wh[1];
                    boolean checked = !autoResolution && captureWidth == w && captureHeight == h;
                    items.add(new MenuAction("Captura: " + w + "x" + h, checked, true, () -> {
                        autoResolution = false;
                        captureWidth = w;
                        captureHeight = h;
                        requestCaptureRestart("Captura " + w + "x" + h);
                    }));
                }
                String[] scales = {"Preservar proporção", "Preencher (cortar bordas)", "Esticar para preencher"};
                for (String sm : scales) {
                    items.add(new MenuAction("Escala: " + sm, sm.equals(scaleMode), true, () -> {
                        scaleMode = sm;
                        showToast("Escala: " + sm);
                    }));
                }
                items.add(new MenuAction("Alternar Tela Cheia (F11)", fullscreen, true,
                        () -> toggleFullscreen(window)));
                String[] gpus = {"Automático (recomendado)", "AMD Radeon", "NVIDIA GeForce / RTX", "Intel / Outra"};
                for (String g : gpus) {
                    items.add(new MenuAction("Preferência GPU: " + g, g.equals(gpuPreference), true, () -> {
                        gpuPreference = g;
                        if (aiRequested) aiReinitRequested.set(true);
                        updateAiStatusSummary();
                        showToast("Preferência de GPU: " + g);
                    }));
                }
            }
            case 2 -> { // Filtros
                items.add(new MenuAction("Abrir / Fechar Painel de Filtros (F2)", showFilterPanel, true, () -> {
                    showFilterPanel = !showFilterPanel;
                }));
                items.add(new MenuAction("Nitidez Adaptativa CAS (S) [" + Math.round((sharpOn ? sharp : 0f) * 100) + "%]",
                        sharpOn, true, () -> {
                            sharpOn = !sharpOn;
                            showToast("Nitidez CAS: " + (sharpOn ? Math.round(sharp * 100) + "%" : "Desligada"));
                        }));
                items.add(new MenuAction("Suavização de Bordas FXAA", fxaaEnabled, true, () -> {
                    fxaaEnabled = !fxaaEnabled;
                    showToast("FXAA: " + (fxaaEnabled ? "Ativado" : "Desativado"));
                }));
                items.add(new MenuAction("Saturação: 100% (Neutra)", Math.abs(saturationLevel - 1.0f) < 0.02f, true, () -> {
                    saturationLevel = 1.0f;
                    showToast("Saturação: 100%");
                }));
                items.add(new MenuAction("Saturação: 110% (Padrão)", Math.abs(saturationLevel - 1.10f) < 0.02f, true, () -> {
                    saturationLevel = 1.10f;
                    showToast("Saturação: 110%");
                }));
                items.add(new MenuAction("Saturação: 135% (Vibrante)", Math.abs(saturationLevel - 1.35f) < 0.02f, true, () -> {
                    saturationLevel = 1.35f;
                    showToast("Saturação: 135%");
                }));
                boolean modelOk = AiSuperResolution.isModelPresent();
                String aiItemLabel = modelOk
                        ? "Super-Resolução por IA (ONNX x2)"
                        : "Super-Resolução por IA (Modelo ONNX ausente)";
                items.add(new MenuAction(aiItemLabel, aiRequested, true, () -> {
                    toggleAiSuperResolution();
                }));
                items.add(new MenuAction("Restaurar Filtros Padrão", false, true, this::resetFiltersToDefault));
            }
            case 3 -> { // Áudio
                items.add(new MenuAction("Silenciar / Reativar Áudio (M)", muted, true, () -> {
                    muted = !muted;
                    showToast(muted ? "Áudio silenciado (Mudo)" : "Áudio reativado (" + Math.round(volume * 100) + "%)");
                }));
                int[] vols = {50, 100, 150, 200};
                for (int v : vols) {
                    boolean active = !muted && Math.abs(volume * 100f - v) < 4f;
                    items.add(new MenuAction("Volume: " + v + "%", active, true, () -> {
                        muted = false;
                        volume = v / 100f;
                        showToast("Volume: " + v + "%");
                    }));
                }
                for (String mix : availableOutputMixers) {
                    String norm = "(Padrão do Sistema)".equals(mix) ? "" : mix;
                    boolean active = norm.equals(outputMixerName == null ? "" : outputMixerName);
                    String display = mix.length() > 38 ? mix.substring(0, 38) + "..." : mix;
                    items.add(new MenuAction("Saída: " + display, active, true, () -> {
                        outputMixerName = norm;
                        audioRestartRequested.set(true);
                    }));
                }
                for (String audIn : knownAudioDevices) {
                    boolean active = audIn.equals(audioDeviceName);
                    String display = audIn.length() > 36 ? audIn.substring(0, 36) + "..." : audIn;
                    items.add(new MenuAction("Entrada: " + display, active, true, () -> {
                        audioDeviceName = audIn;
                        requestCaptureRestart("Entrada de áudio alterada");
                    }));
                }
            }
            case 4 -> { // Opções
                items.add(new MenuAction("Abrir Painel de Opções e Diagnóstico (F3)", showOptionsPanel, true, () -> {
                    showOptionsPanel = !showOptionsPanel;
                }));
                String[] profiles = {"Baixa latência", "Equilibrado", "Qualidade (mais filtros)"};
                for (String prof : profiles) {
                    items.add(new MenuAction("Perfil: " + prof, prof.equals(qualityProfile), true, () -> {
                        applyQualityProfile(prof);
                    }));
                }
                items.add(new MenuAction("Formato Captura: 60 FPS MJPEG", captureFps == 60 && captureMjpeg, true, () -> {
                    captureFps = 60;
                    captureMjpeg = true;
                    requestCaptureRestart("60 FPS MJPEG");
                }));
                items.add(new MenuAction("Formato Captura: 60 FPS YUY2", captureFps == 60 && !captureMjpeg, true, () -> {
                    captureFps = 60;
                    captureMjpeg = false;
                    requestCaptureRestart("60 FPS YUY2");
                }));
                items.add(new MenuAction("Formato Captura: 30 FPS", captureFps == 30, true, () -> {
                    captureFps = 30;
                    captureMjpeg = false;
                    requestCaptureRestart("30 FPS");
                }));
                items.add(new MenuAction("VSync (Sincronização Vertical)", vsyncEnabled, true, () -> {
                    vsyncEnabled = !vsyncEnabled;
                    glfwSwapInterval(vsyncEnabled ? 1 : 0);
                    showToast("VSync: " + (vsyncEnabled ? "Ativado" : "Desativado"));
                }));
                items.add(new MenuAction("Exibir Atalhos de Teclado (F1)", showShortcutsModal, true, () -> {
                    showShortcutsModal = !showShortcutsModal;
                }));
            }
        }
        return items;
    }

    private void toggleAiSuperResolution() {
        if (!aiRequested) {
            if (!AiSuperResolution.isModelPresent()) {
                showToast("Modelo ONNX ausente: coloque " + AiSuperResolution.DEFAULT_MODEL_PATH);
                updateAiStatusSummary();
                return;
            }
            aiRequested = true;
            aiReinitRequested.set(true);
            updateAiStatusSummary();
            showToast("Ativando Super-Resolução por IA...");
        } else {
            aiRequested = false;
            closeAiSafely();
            updateAiStatusSummary();
            showToast("Super-Resolução por IA desativada");
        }
    }

    private void resetFiltersToDefault() {
        sharpOn = true;
        sharp = 0.60f;
        radius = 1.0f;
        saturationLevel = 1.10f;
        fxaaEnabled = false;
        scaleMode = "Preservar proporção";
        if (aiRequested) {
            aiRequested = false;
            closeAiSafely();
        }
        updateAiStatusSummary();
        showToast("Filtros restaurados para o padrão");
    }

    private void applyQualityProfile(String prof) {
        qualityProfile = prof;
        switch (prof) {
            case "Baixa latência" -> {
                sharpOn = true;
                sharp = 0.40f;
                radius = 1.0f;
                fxaaEnabled = false;
            }
            case "Equilibrado" -> {
                sharpOn = true;
                sharp = 0.60f;
                radius = 1.0f;
                fxaaEnabled = false;
            }
            case "Qualidade (mais filtros)" -> {
                sharpOn = true;
                sharp = 0.75f;
                radius = 1.1f;
                fxaaEnabled = true;
            }
        }
        showToast("Perfil aplicado: " + prof);
    }

    private float getMenuX(int index, GlUiRenderer ui) {
        float x = 10f;
        for (int i = 0; i < index; i++) {
            x += ui.measureText(TOP_MENUS[i]) + 24f;
        }
        return x;
    }

    private float getMenuWidth(int index, GlUiRenderer ui) {
        return ui.measureText(TOP_MENUS[index]) + 24f;
    }

    private void handleMousePress(double mx, double my, GlUiRenderer ui) {
        float x = (float) mx;
        float y = (float) my;

        // 1. Modal de atalhos aberto
        if (showShortcutsModal) {
            float mw = 460f, mh = 320f;
            float mx0 = (winW - mw) * 0.5f, my0 = (winH - mh) * 0.5f;
            // Botão fechar modal
            if (inside(x, y, mx0 + mw - 85f, my0 + mh - 38f, 70f, 26f) || !inside(x, y, mx0, my0, mw, mh)) {
                showShortcutsModal = false;
            }
            return;
        }

        // 2. Barra superior de menus (quando em modo janela)
        if (!fullscreen && y <= MENU_BAR_HEIGHT) {
            for (int i = 0; i < TOP_MENUS.length; i++) {
                float bx = getMenuX(i, ui);
                float bw = getMenuWidth(i, ui);
                if (x >= bx && x <= bx + bw) {
                    openMenuIndex = (openMenuIndex == i) ? -1 : i;
                    return;
                }
            }
            // Botões rápidos à direita da barra de menu
            float rightBtnW = 78f;
            float restartX = winW - rightBtnW * 2 - 16f;
            float filtersX = winW - rightBtnW - 8f;
            if (inside(x, y, restartX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f)) {
                openMenuIndex = -1;
                requestCaptureRestart("Botão Reiniciar");
                return;
            }
            if (inside(x, y, filtersX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f)) {
                openMenuIndex = -1;
                showFilterPanel = !showFilterPanel;
                return;
            }
            openMenuIndex = -1;
            return;
        }

        // 3. Dropdown de menu aberto
        if (!fullscreen && openMenuIndex >= 0) {
            List<MenuAction> items = buildMenuItems(openMenuIndex);
            float dropX = getMenuX(openMenuIndex, ui);
            float dropW = 310f;
            if (dropX + dropW > winW - 4) dropX = Math.max(4f, winW - dropW - 4f);
            float itemH = 26f;
            float dropY = MENU_BAR_HEIGHT;
            float dropH = items.size() * itemH + 6f;

            if (inside(x, y, dropX, dropY, dropW, dropH)) {
                int idx = (int) ((y - dropY - 3f) / itemH);
                if (idx >= 0 && idx < items.size()) {
                    MenuAction act = items.get(idx);
                    openMenuIndex = -1;
                    if (act.enabled() && act.action() != null) {
                        act.action().run();
                    }
                }
                return;
            } else {
                openMenuIndex = -1;
            }
        }

        // 4. Painel de Filtros flutuante
        if (showFilterPanel) {
            float pw = 340f, ph = 345f;
            if (inside(x, y, filterPanelX, filterPanelY, pw, ph)) {
                // Botão fechar [X]
                if (inside(x, y, filterPanelX + pw - 28f, filterPanelY + 4f, 24f, 22f)) {
                    showFilterPanel = false;
                    return;
                }
                // Cabeçalho arrastável
                if (inside(x, y, filterPanelX, filterPanelY, pw - 30f, 28f)) {
                    draggingFilterPanel = true;
                    dragOffsetX = x - filterPanelX;
                    dragOffsetY = y - filterPanelY;
                    return;
                }
                // Sliders
                float sx = filterPanelX + 16f, sw = pw - 32f;
                if (inside(x, y, sx, filterPanelY + 52f, sw, 22f)) {
                    activeSliderId = "sharp";
                    updateSliderValue(x, sx, sw);
                    return;
                }
                if (inside(x, y, sx, filterPanelY + 102f, sw, 22f)) {
                    activeSliderId = "radius";
                    updateSliderValue(x, sx, sw);
                    return;
                }
                if (inside(x, y, sx, filterPanelY + 152f, sw, 22f)) {
                    activeSliderId = "saturation";
                    updateSliderValue(x, sx, sw);
                    return;
                }
                // Checkboxes / Toggles
                if (inside(x, y, sx, filterPanelY + 188f, sw, 24f)) {
                    sharpOn = !sharpOn;
                    return;
                }
                if (inside(x, y, sx, filterPanelY + 216f, sw, 24f)) {
                    fxaaEnabled = !fxaaEnabled;
                    return;
                }
                if (inside(x, y, sx, filterPanelY + 244f, sw, 24f)) {
                    toggleAiSuperResolution();
                    return;
                }
                // Botões de escala rápida
                float bw = (sw - 8f) / 3f;
                if (inside(x, y, sx, filterPanelY + 276f, bw, 24f)) {
                    scaleMode = "Preservar proporção";
                    return;
                }
                if (inside(x, y, sx + bw + 4f, filterPanelY + 276f, bw, 24f)) {
                    scaleMode = "Preencher (cortar bordas)";
                    return;
                }
                if (inside(x, y, sx + (bw + 4f) * 2f, filterPanelY + 276f, bw, 24f)) {
                    scaleMode = "Esticar para preencher";
                    return;
                }
                // Botão Restaurar Padrões
                if (inside(x, y, sx, filterPanelY + 308f, sw, 26f)) {
                    resetFiltersToDefault();
                    return;
                }
                return;
            }
        }

        // 5. Painel de Opções e Áudio flutuante
        if (showOptionsPanel) {
            float pw = 350f, ph = 290f;
            if (inside(x, y, optionsPanelX, optionsPanelY, pw, ph)) {
                if (inside(x, y, optionsPanelX + pw - 28f, optionsPanelY + 4f, 24f, 22f)) {
                    showOptionsPanel = false;
                    return;
                }
                if (inside(x, y, optionsPanelX, optionsPanelY, pw - 30f, 28f)) {
                    draggingOptionsPanel = true;
                    dragOffsetX = x - optionsPanelX;
                    dragOffsetY = y - optionsPanelY;
                    return;
                }
                float sx = optionsPanelX + 16f, sw = pw - 32f;
                // Slider de Volume
                if (inside(x, y, sx, optionsPanelY + 52f, sw, 22f)) {
                    activeSliderId = "volume";
                    updateSliderValue(x, sx, sw);
                    return;
                }
                // Botão Mudo
                if (inside(x, y, sx, optionsPanelY + 84f, 120f, 24f)) {
                    muted = !muted;
                    return;
                }
                // Botão VSync
                if (inside(x, y, sx + 130f, optionsPanelY + 84f, sw - 130f, 24f)) {
                    vsyncEnabled = !vsyncEnabled;
                    glfwSwapInterval(vsyncEnabled ? 1 : 0);
                    return;
                }
                // Ciclar Perfil
                if (inside(x, y, sx, optionsPanelY + 118f, sw, 26f)) {
                    String next = switch (qualityProfile) {
                        case "Baixa latência" -> "Equilibrado";
                        case "Equilibrado" -> "Qualidade (mais filtros)";
                        default -> "Baixa latência";
                    };
                    applyQualityProfile(next);
                    return;
                }
                // Ciclar GPU
                if (inside(x, y, sx, optionsPanelY + 150f, sw, 26f)) {
                    String nextGpu = switch (gpuPreference) {
                        case "Automático (recomendado)" -> "AMD Radeon";
                        case "AMD Radeon" -> "NVIDIA GeForce / RTX";
                        case "NVIDIA GeForce / RTX" -> "Intel / Outra";
                        default -> "Automático (recomendado)";
                    };
                    gpuPreference = nextGpu;
                    if (aiRequested) aiReinitRequested.set(true);
                    updateAiStatusSummary();
                    return;
                }
                // Botão Reiniciar Captura
                if (inside(x, y, sx, optionsPanelY + 248f, (sw - 8f) * 0.5f, 26f)) {
                    requestCaptureRestart("Painel Opções");
                    return;
                }
                // Botão Voltar ao Launcher
                if (inside(x, y, sx + (sw + 8f) * 0.5f, optionsPanelY + 248f, (sw - 8f) * 0.5f, 26f)) {
                    returnToLauncherRequested = true;
                    glfwSetWindowShouldClose(window, true);
                    return;
                }
            }
        }
    }

    private void handleMouseDrag(double mx, double my) {
        float x = (float) mx;
        float y = (float) my;
        if (draggingFilterPanel) {
            filterPanelX = Math.max(4f, Math.min(winW - 344f, x - dragOffsetX));
            filterPanelY = Math.max(fullscreen ? 4f : MENU_BAR_HEIGHT + 4f, Math.min(winH - 350f, y - dragOffsetY));
            return;
        }
        if (draggingOptionsPanel) {
            optionsPanelX = Math.max(4f, Math.min(winW - 354f, x - dragOffsetX));
            optionsPanelY = Math.max(fullscreen ? 4f : MENU_BAR_HEIGHT + 4f, Math.min(winH - 295f, y - dragOffsetY));
            return;
        }
        if (activeSliderId != null) {
            if ("volume".equals(activeSliderId)) {
                updateSliderValue(x, optionsPanelX + 16f, 350f - 32f);
            } else {
                updateSliderValue(x, filterPanelX + 16f, 340f - 32f);
            }
        }
    }

    private void updateSliderValue(float mouseX, float sliderX, float sliderW) {
        float t = Math.max(0f, Math.min(1f, (mouseX - sliderX) / sliderW));
        switch (activeSliderId) {
            case "sharp" -> {
                sharp = t;
                sharpOn = sharp > 0.005f;
            }
            case "radius" -> radius = 0.5f + t * 1.5f;
            case "saturation" -> saturationLevel = t * 2.0f;
            case "volume" -> {
                volume = t * 2.0f;
                if (volume > 0.01f) muted = false;
            }
        }
    }

    private static boolean inside(float x, float y, float rx, float ry, float rw, float rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    private void renderUiOverlay(GlUiRenderer ui) {
        ui.begin();
        float mx = (float) mouseX;
        float my = (float) mouseY;

        // 1. Mensagem central quando sem sinal ou inicializando captura
        if (exchangeRef.get() == null || captureHasError) {
            float bw = Math.min(560f, winW - 40f);
            float bh = 110f;
            float bx = (winW - bw) * 0.5f;
            float by = (winH - bh) * 0.5f;
            ui.fillRect(bx, by, bw, bh, 0.10f, 0.11f, 0.14f, 0.92f);
            ui.strokeRect(bx, by, bw, bh, 1f, captureHasError ? 0.85f : 0.30f, 0.35f, 0.40f, 0.95f);
            ui.drawText("Mvisual Monitor — Status da Captura", bx + 18f, by + 16f, 0.95f, 0.96f, 0.98f, 1f);
            String st = captureStatusText;
            if (st.length() > 68) st = st.substring(0, 68) + "...";
            ui.drawText(st, bx + 18f, by + 44f, captureHasError ? 1.0f : 0.75f, captureHasError ? 0.55f : 0.85f, 0.55f, 1f);
            ui.drawText("Use Arquivo > Reiniciar captura (Ctrl+R) ou selecione outro dispositivo no menu.", bx + 18f, by + 74f, 0.68f, 0.72f, 0.78f, 1f);
        }

        // 2. Barra superior de menus (visível no modo janela; oculta em F11 Tela Cheia)
        if (!fullscreen) {
            ui.fillRect(0, 0, winW, MENU_BAR_HEIGHT, 0.11f, 0.12f, 0.15f, 0.98f);
            ui.fillRect(0, MENU_BAR_HEIGHT - 1, winW, 1, 0.24f, 0.26f, 0.32f, 1f);

            for (int i = 0; i < TOP_MENUS.length; i++) {
                float bx = getMenuX(i, ui);
                float bw = getMenuWidth(i, ui);
                boolean hovered = inside(mx, my, bx, 0, bw, MENU_BAR_HEIGHT);
                if (hovered && openMenuIndex >= 0 && openMenuIndex != i) {
                    openMenuIndex = i;
                }
                if (openMenuIndex == i || hovered) {
                    ui.fillRect(bx, 2f, bw, MENU_BAR_HEIGHT - 4f, 0.22f, 0.42f, 0.72f, openMenuIndex == i ? 0.95f : 0.55f);
                }
                ui.drawText(TOP_MENUS[i], bx + 12f, 5f, 0.94f, 0.95f, 0.98f, 1f);
            }

            // Indicador resumido de captura + botões rápidos à direita
            float rightBtnW = 78f;
            float restartX = winW - rightBtnW * 2 - 16f;
            float filtersX = winW - rightBtnW - 8f;

            String infoStatus = (actualInputWidth > 0 ? actualInputWidth + "x" + actualInputHeight : "Sem sinal")
                    + (measuredCaptureFps > 0.5 ? String.format(" %.0ffps", measuredCaptureFps) : "")
                    + (muted ? " [Mudo]" : "");
            float infoW = ui.measureText(infoStatus);
            if (restartX - infoW - 20f > getMenuX(TOP_MENUS.length, ui)) {
                ui.drawText(infoStatus, restartX - infoW - 14f, 5f, 0.65f, 0.75f, 0.88f, 1f);
            }

            boolean hovRestart = inside(mx, my, restartX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f);
            ui.fillRect(restartX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f, hovRestart ? 0.28f : 0.18f, hovRestart ? 0.34f : 0.20f, hovRestart ? 0.45f : 0.26f, 1f);
            ui.strokeRect(restartX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f, 1f, 0.32f, 0.36f, 0.44f, 1f);
            ui.drawText("Reiniciar", restartX + 12f, 5f, 0.92f, 0.95f, 1f, 1f);

            boolean hovFilters = inside(mx, my, filtersX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f);
            ui.fillRect(filtersX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f,
                    showFilterPanel ? 0.22f : (hovFilters ? 0.28f : 0.18f),
                    showFilterPanel ? 0.46f : (hovFilters ? 0.34f : 0.20f),
                    showFilterPanel ? 0.76f : (hovFilters ? 0.45f : 0.26f), 1f);
            ui.strokeRect(filtersX, 3f, rightBtnW, MENU_BAR_HEIGHT - 6f, 1f, 0.32f, 0.36f, 0.44f, 1f);
            ui.drawText("Filtros", filtersX + 19f, 5f, 0.92f, 0.95f, 1f, 1f);
        }

        // 3. Painel de Filtros em Tempo Real
        if (showFilterPanel) {
            renderFilterPanel(ui, mx, my);
        }

        // 4. Painel de Opções, Áudio e Diagnóstico
        if (showOptionsPanel) {
            renderOptionsPanel(ui, mx, my);
        }

        // 5. Dropdown do Menu ativo (desenhado por cima dos painéis)
        if (!fullscreen && openMenuIndex >= 0) {
            List<MenuAction> items = buildMenuItems(openMenuIndex);
            float dropX = getMenuX(openMenuIndex, ui);
            float dropW = 310f;
            if (dropX + dropW > winW - 4) dropX = Math.max(4f, winW - dropW - 4f);
            float itemH = 26f;
            float dropY = MENU_BAR_HEIGHT;
            float dropH = items.size() * itemH + 6f;

            ui.fillRect(dropX, dropY, dropW, dropH, 0.13f, 0.14f, 0.18f, 0.97f);
            ui.strokeRect(dropX, dropY, dropW, dropH, 1f, 0.28f, 0.32f, 0.40f, 1f);

            for (int i = 0; i < items.size(); i++) {
                MenuAction item = items.get(i);
                float iy = dropY + 3f + i * itemH;
                boolean hov = inside(mx, my, dropX + 2f, iy, dropW - 4f, itemH);
                if (hov && item.enabled()) {
                    ui.fillRect(dropX + 3f, iy, dropW - 6f, itemH, 0.22f, 0.44f, 0.74f, 0.90f);
                }
                String prefix = item.checked() ? "[x] " : "    ";
                ui.drawText(prefix + item.label(), dropX + 10f, iy + 4f,
                        item.enabled() ? 0.94f : 0.50f,
                        item.enabled() ? 0.95f : 0.52f,
                        item.enabled() ? 0.98f : 0.55f, 1f);
            }
        }

        // 6. Modal de Atalhos (F1)
        if (showShortcutsModal) {
            renderShortcutsModal(ui, mx, my);
        }

        // 7. Toast / Notificação rápida no canto inferior direito
        if (System.nanoTime() < toastExpireNano && toastMessage != null && !toastMessage.isEmpty()) {
            float tw = ui.measureText(toastMessage) + 28f;
            float th = 30f;
            float tx = winW - tw - 16f;
            float ty = winH - th - 16f;
            ui.fillRect(tx, ty, tw, th, 0.10f, 0.12f, 0.16f, 0.90f);
            ui.strokeRect(tx, ty, tw, th, 1f, 0.28f, 0.52f, 0.85f, 0.95f);
            ui.drawText(toastMessage, tx + 14f, ty + 6f, 0.95f, 0.97f, 1.0f, 1f);
        }

        ui.flush(winW, winH);
    }

    private void renderFilterPanel(GlUiRenderer ui, float mx, float my) {
        float pw = 340f, ph = 345f;
        float px = filterPanelX, py = filterPanelY;
        ui.fillRect(px, py, pw, ph, 0.12f, 0.13f, 0.17f, 0.95f);
        ui.fillRect(px, py, pw, 28f, 0.17f, 0.20f, 0.26f, 0.98f);
        ui.strokeRect(px, py, pw, ph, 1f, 0.30f, 0.35f, 0.44f, 1f);
        ui.drawText("Painel de Filtros em Tempo Real", px + 12f, py + 5f, 0.95f, 0.96f, 1f, 1f);

        // Botão fechar [X]
        boolean hovClose = inside(mx, my, px + pw - 28f, py + 4f, 24f, 20f);
        if (hovClose) ui.fillRect(px + pw - 28f, py + 4f, 24f, 20f, 0.75f, 0.22f, 0.22f, 1f);
        ui.drawText("X", px + pw - 20f, py + 5f, 0.95f, 0.95f, 0.95f, 1f);

        float sx = px + 16f, sw = pw - 32f;

        // Slider Nitidez
        ui.drawText("Nitidez CAS: " + Math.round((sharpOn ? sharp : 0f) * 100) + "%", sx, py + 34f, 0.88f, 0.90f, 0.95f, 1f);
        drawSlider(ui, sx, py + 54f, sw, sharpOn ? sharp : 0f);

        // Slider Raio
        ui.drawText(String.format("Raio do Filtro: %.2f px", radius), sx, py + 84f, 0.88f, 0.90f, 0.95f, 1f);
        drawSlider(ui, sx, py + 104f, sw, (radius - 0.5f) / 1.5f);

        // Slider Saturação
        ui.drawText("Saturação de Cor: " + Math.round(saturationLevel * 100) + "%", sx, py + 134f, 0.88f, 0.90f, 0.95f, 1f);
        drawSlider(ui, sx, py + 154f, sw, saturationLevel / 2.0f);

        // Checkboxes
        drawCheckbox(ui, sx, py + 188f, "Ativar Nitidez Adaptativa (Tecla S)", sharpOn, inside(mx, my, sx, py + 188f, sw, 22f));
        drawCheckbox(ui, sx, py + 216f, "Suavização Espacial de Bordas (FXAA)", fxaaEnabled, inside(mx, my, sx, py + 216f, sw, 22f));
        drawCheckbox(ui, sx, py + 244f, "Super-Resolução por IA (ONNX x2)", aiRequested, inside(mx, my, sx, py + 244f, sw, 22f));

        // Botões de Escala
        float bw = (sw - 8f) / 3f;
        drawSmallButton(ui, sx, py + 276f, bw, 24f, "Proporção", "Preservar proporção".equals(scaleMode), inside(mx, my, sx, py + 276f, bw, 24f));
        drawSmallButton(ui, sx + bw + 4f, py + 276f, bw, 24f, "Preencher", "Preencher (cortar bordas)".equals(scaleMode), inside(mx, my, sx + bw + 4f, py + 276f, bw, 24f));
        drawSmallButton(ui, sx + (bw + 4f) * 2f, py + 276f, bw, 24f, "Esticar", "Esticar para preencher".equals(scaleMode), inside(mx, my, sx + (bw + 4f) * 2f, py + 276f, bw, 24f));

        // Botão Restaurar Padrões
        drawSmallButton(ui, sx, py + 308f, sw, 26f, "Restaurar Valores Padrão", false, inside(mx, my, sx, py + 308f, sw, 26f));
    }

    private void renderOptionsPanel(GlUiRenderer ui, float mx, float my) {
        float pw = 350f, ph = 290f;
        float px = optionsPanelX, py = optionsPanelY;
        ui.fillRect(px, py, pw, ph, 0.12f, 0.13f, 0.17f, 0.95f);
        ui.fillRect(px, py, pw, 28f, 0.17f, 0.20f, 0.26f, 0.98f);
        ui.strokeRect(px, py, pw, ph, 1f, 0.30f, 0.35f, 0.44f, 1f);
        ui.drawText("Opções de Áudio, GPU e Desempenho", px + 12f, py + 5f, 0.95f, 0.96f, 1f, 1f);

        boolean hovClose = inside(mx, my, px + pw - 28f, py + 4f, 24f, 20f);
        if (hovClose) ui.fillRect(px + pw - 28f, py + 4f, 24f, 20f, 0.75f, 0.22f, 0.22f, 1f);
        ui.drawText("X", px + pw - 20f, py + 5f, 0.95f, 0.95f, 0.95f, 1f);

        float sx = px + 16f, sw = pw - 32f;

        ui.drawText("Volume do Áudio: " + (muted ? "MUDO (0%)" : Math.round(volume * 100) + "%"), sx, py + 34f, 0.88f, 0.90f, 0.95f, 1f);
        drawSlider(ui, sx, py + 54f, sw, muted ? 0f : volume / 2.0f);

        drawSmallButton(ui, sx, py + 84f, 120f, 24f, muted ? "Reativar Som (M)" : "Silenciar (M)", muted, inside(mx, my, sx, py + 84f, 120f, 24f));
        drawSmallButton(ui, sx + 130f, py + 84f, sw - 130f, 24f, "VSync: " + (vsyncEnabled ? "Ativo" : "Desligado"), vsyncEnabled, inside(mx, my, sx + 130f, py + 84f, sw - 130f, 24f));

        drawSmallButton(ui, sx, py + 118f, sw, 26f, "Perfil: " + qualityProfile, false, inside(mx, my, sx, py + 118f, sw, 26f));
        drawSmallButton(ui, sx, py + 150f, sw, 26f, "Preferência GPU: " + gpuPreference, false, inside(mx, my, sx, py + 150f, sw, 26f));

        ui.drawText("Status Captura: " + (actualInputWidth > 0 ? actualInputWidth + "x" + actualInputHeight : "Aguardando"), sx, py + 186f, 0.75f, 0.82f, 0.92f, 1f);
        String aiSt = aiStatusSummary;
        if (aiSt.length() > 46) aiSt = aiSt.substring(0, 46) + "...";
        ui.drawText("Status IA: " + aiSt, sx, py + 210f, 0.75f, 0.82f, 0.92f, 1f);

        drawSmallButton(ui, sx, py + 248f, (sw - 8f) * 0.5f, 26f, "Reiniciar Captura", false, inside(mx, my, sx, py + 248f, (sw - 8f) * 0.5f, 26f));
        drawSmallButton(ui, sx + (sw + 8f) * 0.5f, py + 248f, (sw - 8f) * 0.5f, 26f, "Voltar ao Launcher", false, inside(mx, my, sx + (sw + 8f) * 0.5f, py + 248f, (sw - 8f) * 0.5f, 26f));
    }

    private void renderShortcutsModal(GlUiRenderer ui, float mx, float my) {
        float mw = 460f, mh = 320f;
        float x0 = (winW - mw) * 0.5f, y0 = (winH - mh) * 0.5f;
        ui.fillRect(0, 0, winW, winH, 0f, 0f, 0f, 0.55f);
        ui.fillRect(x0, y0, mw, mh, 0.13f, 0.14f, 0.18f, 0.98f);
        ui.strokeRect(x0, y0, mw, mh, 1f, 0.34f, 0.40f, 0.52f, 1f);
        ui.drawText("Atalhos de Teclado do Monitor Mvisual", x0 + 18f, y0 + 14f, 0.96f, 0.97f, 1f, 1f);

        String[] lines = {
                "F11             Alternar modo Janela / Tela Cheia sem bordas",
                "S               Ligar / Desligar Nitidez Adaptativa (CAS)",
                "Cima / Baixo    Aumentar / Diminuir intensidade da Nitidez",
                "[ e ]           Diminuir / Aumentar Saturação de Cor",
                "M               Silenciar / Reativar o Áudio",
                "+ e -           Aumentar / Diminuir o Volume do Áudio",
                "Ctrl + R        Reiniciar a captura atual com segurança",
                "F2 / F3         Abrir / Fechar Painel de Filtros / Opções",
                "Esc             Fechar painel ativo ou sair do Monitor"
        };
        for (int i = 0; i < lines.length; i++) {
            ui.drawText(lines[i], x0 + 22f, y0 + 48f + i * 24f, 0.85f, 0.88f, 0.94f, 1f);
        }
        drawSmallButton(ui, x0 + mw - 85f, y0 + mh - 38f, 70f, 26f, "Fechar", true, inside(mx, my, x0 + mw - 85f, y0 + mh - 38f, 70f, 26f));
    }

    private static void drawSlider(GlUiRenderer ui, float x, float y, float w, float normVal) {
        float clamped = Math.max(0f, Math.min(1f, normVal));
        ui.fillRect(x, y + 6f, w, 6f, 0.22f, 0.24f, 0.30f, 1f);
        ui.fillRect(x, y + 6f, w * clamped, 6f, 0.28f, 0.56f, 0.92f, 1f);
        float knobX = x + w * clamped - 5f;
        ui.fillRect(knobX, y + 1f, 10f, 16f, 0.92f, 0.95f, 1.0f, 1f);
    }

    private static void drawCheckbox(GlUiRenderer ui, float x, float y, String label, boolean checked, boolean hovered) {
        ui.fillRect(x, y + 2f, 16f, 16f, checked ? 0.25f : 0.18f, checked ? 0.54f : 0.20f, checked ? 0.88f : 0.25f, 1f);
        ui.strokeRect(x, y + 2f, 16f, 16f, 1f, hovered ? 0.65f : 0.40f, hovered ? 0.75f : 0.44f, 0.90f, 1f);
        if (checked) {
            ui.fillRect(x + 4f, y + 6f, 8f, 8f, 0.96f, 0.98f, 1f, 1f);
        }
        ui.drawText(label, x + 24f, y + 1f, 0.90f, 0.92f, 0.96f, 1f);
    }

    private static void drawSmallButton(GlUiRenderer ui, float x, float y, float w, float h, String text, boolean active, boolean hovered) {
        float r = active ? 0.24f : (hovered ? 0.26f : 0.18f);
        float g = active ? 0.48f : (hovered ? 0.30f : 0.20f);
        float b = active ? 0.80f : (hovered ? 0.38f : 0.26f);
        ui.fillRect(x, y, w, h, r, g, b, 1f);
        ui.strokeRect(x, y, w, h, 1f, 0.35f, 0.40f, 0.50f, 1f);
        float tw = ui.measureText(text);
        ui.drawText(text, x + (w - tw) * 0.5f, y + (h - ui.lineHeight) * 0.5f - 1f, 0.95f, 0.96f, 1f, 1f);
    }

    // --------------------------------------------------- THREAD 2: render (main)

    private void renderLoop() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Falha ao iniciar GLFW");

        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vm = glfwGetVideoMode(monitor);
        if (vm == null) throw new IllegalStateException("Sem video mode do monitor");

        int mx, my;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer x = st.mallocInt(1), y = st.mallocInt(1);
            glfwGetMonitorPos(monitor, x, y);
            mx = x.get(0);
            my = y.get(0);
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_AUTO_ICONIFY, GLFW_FALSE);
        glfwWindowHint(GLFW_RED_BITS, vm.redBits());
        glfwWindowHint(GLFW_GREEN_BITS, vm.greenBits());
        glfwWindowHint(GLFW_BLUE_BITS, vm.blueBits());
        glfwWindowHint(GLFW_REFRESH_RATE, vm.refreshRate());

        windowedW = Math.min(1280, Math.max(800, vm.width() - 160));
        windowedH = Math.min(720 + MENU_BAR_HEIGHT, Math.max(540, vm.height() - 140));
        windowedX = mx + (vm.width() - windowedW) / 2;
        windowedY = my + (vm.height() - windowedH) / 2;

        window = glfwCreateWindow(windowedW, windowedH, "Mvisual Monitor — " + deviceName, NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Falha ao criar janela (OpenGL 3.3 indisponível?)");
        glfwSetWindowSizeLimits(window, 640, 380, GLFW_DONT_CARE, GLFW_DONT_CARE);
        glfwSetWindowPos(window, windowedX, windowedY);
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(vsyncEnabled ? 1 : 0);
        applyWindowIcon();

        GlUiRenderer uiRenderer = new GlUiRenderer();

        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> {
            if (action == GLFW_RELEASE) return;
            boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
            switch (key) {
                case GLFW_KEY_ESCAPE -> {
                    if (action == GLFW_PRESS) {
                        if (showShortcutsModal) showShortcutsModal = false;
                        else if (openMenuIndex >= 0) openMenuIndex = -1;
                        else if (showFilterPanel) showFilterPanel = false;
                        else if (showOptionsPanel) showOptionsPanel = false;
                        else glfwSetWindowShouldClose(w, true);
                    }
                }
                case GLFW_KEY_F11 -> {
                    if (action == GLFW_PRESS) toggleFullscreen(w);
                }
                case GLFW_KEY_F1 -> {
                    if (action == GLFW_PRESS) showShortcutsModal = !showShortcutsModal;
                }
                case GLFW_KEY_F2 -> {
                    if (action == GLFW_PRESS) showFilterPanel = !showFilterPanel;
                }
                case GLFW_KEY_F3 -> {
                    if (action == GLFW_PRESS) showOptionsPanel = !showOptionsPanel;
                }
                case GLFW_KEY_R -> {
                    if (action == GLFW_PRESS && ctrl) requestCaptureRestart("Atalho Ctrl+R");
                }
                case GLFW_KEY_M -> {
                    if (action == GLFW_PRESS) {
                        muted = !muted;
                        showToast(muted ? "Áudio: Mudo" : "Áudio: " + Math.round(volume * 100) + "%");
                    }
                }
                case GLFW_KEY_EQUAL, GLFW_KEY_KP_ADD -> {
                    if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                        muted = false;
                        volume = Math.min(2.0f, volume + 0.05f);
                        showToast("Volume: " + Math.round(volume * 100) + "%");
                    }
                }
                case GLFW_KEY_MINUS, GLFW_KEY_KP_SUBTRACT -> {
                    if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                        volume = Math.max(0.0f, volume - 0.05f);
                        showToast("Volume: " + Math.round(volume * 100) + "%");
                    }
                }
                case GLFW_KEY_S -> {
                    if (action == GLFW_PRESS) {
                        sharpOn = !sharpOn;
                        showToast("Nitidez CAS: " + (sharpOn ? Math.round(sharp * 100) + "%" : "Desligada"));
                    }
                }
                case GLFW_KEY_UP -> {
                    sharpOn = true;
                    sharp = Math.min(1f, sharp + 0.05f);
                    showToast("Nitidez CAS: " + Math.round(sharp * 100) + "%");
                }
                case GLFW_KEY_DOWN -> {
                    sharp = Math.max(0f, sharp - 0.05f);
                    showToast("Nitidez CAS: " + Math.round(sharp * 100) + "%");
                }
                case GLFW_KEY_RIGHT_BRACKET -> {
                    saturationLevel = Math.min(2.0f, saturationLevel + 0.05f);
                    showToast("Saturação: " + Math.round(saturationLevel * 100) + "%");
                }
                case GLFW_KEY_LEFT_BRACKET -> {
                    saturationLevel = Math.max(0.0f, saturationLevel - 0.05f);
                    showToast("Saturação: " + Math.round(saturationLevel * 100) + "%");
                }
                default -> { }
            }
        });

        glfwSetCursorPosCallback(window, (w, xpos, ypos) -> {
            mouseX = xpos;
            mouseY = ypos;
            if (mouseDown) {
                handleMouseDrag(xpos, ypos);
            }
        });

        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW_PRESS) {
                    mouseDown = true;
                    handleMousePress(mouseX, mouseY, uiRenderer);
                } else if (action == GLFW_RELEASE) {
                    mouseDown = false;
                    activeSliderId = null;
                    draggingFilterPanel = false;
                    draggingOptionsPanel = false;
                }
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
                openMenuIndex = -1;
                showFilterPanel = !showFilterPanel;
            }
        });

        glfwSetFramebufferSizeCallback(window, (w, nw, nh) -> {
            fbW = Math.max(1, nw);
            fbH = Math.max(1, nh);
        });
        glfwSetWindowSizeCallback(window, (w, nw, nh) -> {
            winW = Math.max(1, nw);
            winH = Math.max(1, nh);
        });

        glfwShowWindow(window);
        glfwFocusWindow(window);

        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer w = st.mallocInt(1), h = st.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            fbW = Math.max(1, w.get(0));
            fbH = Math.max(1, h.get(0));
            glfwGetWindowSize(window, w, h);
            winW = Math.max(1, w.get(0));
            winH = Math.max(1, h.get(0));
        }

        System.out.println("[render] Monitor " + vm.width() + "x" + vm.height() + " @ " + vm.refreshRate() + " Hz | GL "
                + glGetString(GL_VERSION));

        int program = buildProgram(VERT, FRAG);
        int uTex = glGetUniformLocation(program, "uTex");
        int uTexel = glGetUniformLocation(program, "uTexel");
        int uSharp = glGetUniformLocation(program, "uSharp");
        int uRadius = glGetUniformLocation(program, "uRadius");
        int uSaturation = glGetUniformLocation(program, "uSaturation");
        int uFxaa = glGetUniformLocation(program, "uFxaa");
        int uAiSr = glGetUniformLocation(program, "uAiSr");
        int uAiParams = glGetUniformLocation(program, "uAiParams");
        int uUvScale = glGetUniformLocation(program, "uUvScale");

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int tex = glGenTextures();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glUseProgram(program);
        glUniform1i(uTex, 0);
        glClearColor(0.04f, 0.04f, 0.05f, 1f);

        FrameExchange currentEx = null;
        int texW = 0, texH = 0;
        long uploads = 0, t0 = System.nanoTime();

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            // Libera com segurança buffers antigos de resoluções anteriores
            if (!retiredExchanges.isEmpty()) {
                for (FrameExchange old : retiredExchanges) {
                    if (old != currentEx) {
                        old.free();
                        retiredExchanges.remove(old);
                    }
                }
            }

            FrameExchange latestEx = exchangeRef.get();
            if (latestEx != null && (latestEx != currentEx || latestEx.width != texW || latestEx.height != texH)) {
                currentEx = latestEx;
                texW = currentEx.width;
                texH = currentEx.height;
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, tex);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, texW, texH, 0, GL_BGR, GL_UNSIGNED_BYTE, 0L);
            }

            glViewport(0, 0, fbW, fbH);
            glClear(GL_COLOR_BUFFER_BIT);

            float scaleYFactor = (float) fbH / Math.max(1, winH);
            int topBarPx = fullscreen ? 0 : Math.round(MENU_BAR_HEIGHT * scaleYFactor);
            int videoAreaW = fbW;
            int videoAreaH = Math.max(1, fbH - topBarPx);

            if (currentEx != null && texW > 0 && texH > 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, tex);
                ByteBuffer latest = currentEx.acquireLatest();
                if (latest != null) {
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, texW, texH,
                            GL_BGR, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(latest));
                    uploads++;
                }

                int vw = videoAreaW, vh = videoAreaH;
                String sm = this.scaleMode;
                if ("Preservar proporção".equals(sm)) {
                    if ((long) videoAreaW * texH > (long) videoAreaH * texW) {
                        vw = (int) ((long) videoAreaH * texW / texH);
                    } else {
                        vh = (int) ((long) videoAreaW * texH / texW);
                    }
                }
                int vx = (videoAreaW - vw) / 2;
                int vy = (videoAreaH - vh) / 2; // OpenGL y=0 é na base da janela, logo a área de vídeo fica [0 .. fbH - topBarPx]
                glViewport(vx, vy, Math.max(1, vw), Math.max(1, vh));

                float uvX = 1.0f, uvY = 1.0f;
                if ("Preencher (cortar bordas)".equals(sm)) {
                    float sourceAspect = (float) texW / texH;
                    float targetAspect = (float) videoAreaW / videoAreaH;
                    if (targetAspect > sourceAspect) uvY = sourceAspect / targetAspect;
                    else uvX = targetAspect / sourceAspect;
                }

                glUseProgram(program);
                glUniform1i(uTex, 0);
                glUniform2f(uTexel, 1f / texW, 1f / texH);
                glUniform2f(uUvScale, uvX, uvY);
                glUniform1f(uSharp, sharpOn ? sharp : 0f);
                glUniform1f(uRadius, radius);
                glUniform1f(uSaturation, saturationLevel);
                glUniform1i(uFxaa, fxaaEnabled ? 1 : 0);
                AiSuperResolution activeAi = this.aiProcessor;
                boolean aiOn = this.aiRequested && activeAi != null && !activeAi.isClosed();
                glUniform1i(uAiSr, aiOn ? 1 : 0);
                if (aiOn) {
                    glUniform3f(uAiParams, activeAi.neuralSharpBoost(), activeAi.neuralDenoise(), activeAi.neuralContrast());
                } else {
                    glUniform3f(uAiParams, 1.0f, 0.55f, 1.08f);
                }
                glBindVertexArray(vao);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }

            // Renderiza a Barra de Menus e Painéis OSD em viewport completo
            glViewport(0, 0, fbW, fbH);
            renderUiOverlay(uiRenderer);

            glfwSwapBuffers(window);

            if (debugEnabled && System.nanoTime() - t0 > 2_000_000_000L) {
                double sec = (System.nanoTime() - t0) / 1e9;
                System.out.printf("[render] %.1f uploads/s%n", uploads / sec);
                uploads = 0;
                t0 = System.nanoTime();
            }
        }

        // Limpeza segura
        running = false;
        uiRenderer.destroy();
        glDeleteTextures(tex);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
        glfwDestroyWindow(window);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }

    // ------------------------------------------------------------- helpers GL

    private static int buildProgram(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs);
        int f = compile(GL_FRAGMENT_SHADER, fs);
        int p = glCreateProgram();
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Link falhou: " + glGetProgramInfoLog(p));
        }
        glDeleteShader(v);
        glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader falhou: " + glGetShaderInfoLog(s));
        }
        return s;
    }
}
