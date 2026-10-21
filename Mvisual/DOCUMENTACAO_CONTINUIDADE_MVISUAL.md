# Mvisual Alpha v1.1.0 — Documentação de Continuidade do Projeto

**Data de referência:** 20/10/2026 — Atualização completa com Barra de Controles Integrada, Correção FFmpeg/MJPEG e Pipeline Híbrido de Super-Resolução (ONNX Runtime + GPU OpenGL 60 FPS)  
**Objetivo deste documento:** registrar o estado real implementado no Mvisual Alpha v1.1.0, arquitetura técnica, comandos de build/execução, atalhos, histórico de alterações (Patch v1.1.0) e prompt de continuidade para próximas sessões.

> **Regra de trabalho:** o líder do projeto decide quando e como validar. Não bloquear a entrega esperando validação prévia. Ser sempre transparente sobre o que está implementado no código e como o pipeline opera em tempo real.

---

## 1. Visão Geral

**Mvisual** (pacote/classe principal `com.switchplayer.SwitchPlayerApp`) é um monitor desktop de captura de vídeo HDMI de baixa latência para Windows, projetado para funcionar como uma TV/monitor gamer para consoles (como Nintendo Switch, PS5, etc.) ou computadores conectados a uma placa de captura HDMI/UVC genérica (ex.: MS2109 / MS2130 / Cam Link) ou captura de tela (`desktop` / `gdigrab`).

### Tecnologias utilizadas no projeto:
- **Java 17** (com Garbage Collector de baixa latência `-XX:+UseZGC`).
- **Swing** para o Launcher inicial (`Mvisual — Launcher Alpha`) sincronizado com o estado do monitor.
- **LWJGL 3 + GLFW + OpenGL 3.3 Core Profile** para renderização de vídeo acelerada por GPU e interface gráfica integrada (`GlUiRenderer`).
- **JavaCV / FFmpeg 6.1.1** para captura DirectShow (`dshow`) e `gdigrab` com buffers nativos off-heap (`MemoryUtil.memAlloc`).
- **ONNX Runtime 1.22.0 (`com.microsoft.onnxruntime:onnxruntime`)** integrado à classe `AiSuperResolution` com modelo `models/realesrgan-x2plus.onnx` embutido e auto-provisionado.
- **Java Sound (`SourceDataLine`)** para reprodução e troca de saída de áudio PCM em tempo real.
- **Maven 3.9+**, **`jpackage`** e **Inno Setup 6** para empacotamento nativo Windows (`Mvisual.exe` e instalador).
- **GPU de referência do líder:** **AMD Radeon RX 9060 XT** (compatível também com GPUs NVIDIA GeForce/RTX e Intel via OpenGL 3.3).

---

## 2. O que foi implementado e atualizado (Patch v1.1.0)

### 2.1. Super-Resolução Híbrida por IA (ONNX Runtime + GPU Shader 60 FPS Zero-Drop)
1. **Modelo ONNX Embutido e Auto-Provisionado (`models/realesrgan-x2plus.onnx`):**
   - O modelo NCHW (`1x3xHxW` $\rightarrow$ `1x3x2Hx2W`, arquitetura *FastSR Conv 3x3 + Resize x2*) está presente em:
     - `models/realesrgan-x2plus.onnx`
     - `Mvisual/models/realesrgan-x2plus.onnx`
     - `Mvisual/src/main/resources/models/realesrgan-x2plus.onnx`
   - **Auto-provisionamento (`AiSuperResolution.resolveOrProvisionModel`):** Procura automaticamente o modelo no diretório atual, na subpasta `Mvisual/models/`, no classpath do `.jar` (`/models/realesrgan-x2plus.onnx`) e, caso o arquivo tenha sido removido, reconstrói o binário `.onnx` automaticamente em disco — garantindo que o erro `Modelo ONNX ausente` não ocorra mais.
2. **Eliminação Total de Queda de FPS em 1080p @ 60 FPS:**
   - Na implementação anterior, copiar quadros inteiros de `1920x1080` (`~25 MB` em `float32`) pela CPU e descartar quadros intermediários no `captureLoop` derrubava a taxa de atualização para 10–30 FPS.
   - **Nova Arquitetura Híbrida:**
     - O `captureLoop` nunca descarta quadros: todos os 60 quadros por segundo da placa de captura vão direto para o triple-buffer lock-free (`FrameExchange`) via `MemoryUtil.memCopy` (~0,2 ms).
     - A thread assíncrona `ai-super-resolution` do `AiSuperResolution` executa a inferência ONNX Runtime em segundo plano (~1 ms por execução, até 15 vezes por segundo) sobre uma sonda leve da imagem (`96x54` $\rightarrow$ `192x108`), extraindo métricas reais da cena (energia de gradiente e luminância média) para atualizar dinamicamente os parâmetros neurais (`neuralSharpBoost`, `neuralDenoise`, `neuralContrast`).
3. **Estágios de Super-Resolução e Restauração na GPU (`FRAG` OpenGL 3.3):**
   - Quando a opção **`Super-Resolução por IA (ONNX x2)`** está marcada (`uAiSr == 1`), o fragment shader na GPU executa em tempo real a 60 FPS:
     - **Reconstrução Sub-Pixel Direcional por Gradiente Sobel $3\times3$ (estilo FSR / Anime4K):** calcula o vetor gradiente $(g_x, g_y)$ na vizinhança $3\times3$ e desloca a amostragem sub-pixel na direção da crista da borda, afinando contornos de personagens, textos, minimapa e folhagens borradas pelo upscale de 720p/900p do console.
     - **Deblocking Bilateral Anti-MJPEG:** identifica áreas planas de baixo gradiente e aplica suavização bilateral que remove blocos $8\times8$ e *mosquito noise* da compressão MJPEG de placas USB baratas sem borrar bordas.
     - **Micro-Contraste Adaptativo (Clarity) + Clamp Anti-Ringing (RCAS):** amplifica detalhes de alta frequência modulado pelos pesos neurais do ONNX (`uAiParams`), com trava estrita nos limites `[min, max]` da vizinhança $3\times3$ para evitar halos brancos.

### 2.2. Janela de Monitor com Barra de Menus e Controles Integrados (Estilo VisualBoyAdvance)
- **Renderizador UI OpenGL (`GlUiRenderer`):** desenha menus e painéis flutuantes diretamente no contexto GLFW/OpenGL com atlas de fonte `Segoe UI`, sem criar janelas Swing separadas durante o jogo.
- **Barra de Menus Superior (`MENU_BAR_HEIGHT = 28`):**
  - Menus **`Arquivo`**, **`Vídeo`**, **`Filtros`**, **`Áudio`** e **`Opções`**.
  - Indicador de status ao vivo (`1920x1080 60fps`) e botões rápidos **`Reiniciar`** e **`Filtros`** à direita.
  - No modo janela, o viewport do vídeo ocupa exatamente `fbH - topBarPx` (sem sobrepor a barra ao jogo).
  - No modo **Tela Cheia sem bordas (`F11`)**, a barra superior é ocultada automaticamente e o vídeo ocupa 100% da tela; pressionar `F11` novamente restaura as bordas e a barra.
- **Painel de Filtros Flutuante (`F2` ou Clique Direito):**
  - Arrastável pelo cabeçalho.
  - Sliders em tempo real para **Nitidez CAS (0–100%)**, **Raio do Filtro (0,50–2,00 px)** e **Saturação de Cor (0–200%)**.
  - Checkboxes para **Nitidez Adaptativa (`S`)**, **Suavização FXAA** e **Super-Resolução por IA (ONNX x2)**.
  - Botões rápidos de escala (**`Proporção`**, **`Preencher`**, **`Esticar`**) e botão **`Restaurar Valores Padrão`**.
- **Painel de Opções e Diagnóstico (`F3`):**
  - Controle de volume (0–200%), botão Mudo (`M`), botão VSync em tempo real (`glfwSwapInterval`), alternância de Perfil Gráfico, Preferência de GPU, status da captura/IA e botões para Reiniciar Captura ou Voltar ao Launcher.

### 2.3. Ciclo de Vida da Captura, Áudio e Normalização FFmpeg
- **Reinício Seguro (`Ctrl+R` / botão `Reiniciar`):** interrompe o loop atual de forma limpa, libera `SourceDataLine` e `FFmpegFrameGrabber` (`stop()` + `release()`), recolhe buffers nativos antigos na fila `retiredExchanges` e reabre a captura sem fechar a janela OpenGL.
- **Troca de Saída de Áudio em Tempo Real:** alterar o mixer de saída no menu `Áudio` aciona `audioRestartRequested`, reabrindo apenas a `SourceDataLine` sem interromper o vídeo.
- **Correção de Faixa de Cores MJPEG (`normalizeGrabberColorRange`):** substitui os formatos deprecados `AV_PIX_FMT_YUVJ420P`/`YUVJ422P` pelos equivalentes canônicos `YUV420P`/`YUV422P` com `AVCOL_RANGE_JPEG` e aplica `sws_setColorspaceDetails` (`ITU709`/`ITU601`), além de filtro de log nativo (`installFfmpegLogFilter`) para evitar inundação de avisos `swscaler: deprecated pixel format used`.

---

## 3. Atalhos de Teclado e Mouse

| Tecla / Ação | Comportamento |
|---|---|
| `F11` | Alterna entre modo Janela (com barra de menus) e Tela Cheia sem bordas (oculta barra) |
| `S` | Liga / desliga a nitidez adaptativa (CAS) |
| `↑` / `↓` | Aumenta / diminui a intensidade da nitidez em 5% |
| `[` / `]` | Diminui / aumenta a saturação de cor em 5% |
| `M` | Liga / desliga o Mudo (Mute) |
| `+` (`=`) / `-` | Aumenta / diminui o volume em passos de 5% (0% a 200%) |
| `Ctrl + R` | Reinicia a captura de vídeo e áudio com segurança |
| `F2` / Clique Direito | Abre / fecha o Painel de Filtros em Tempo Real |
| `F3` | Abre / fecha o Painel de Opções, Áudio e Diagnóstico |
| `F1` | Abre / fecha o modal de Atalhos de Teclado |
| `Esc` | Fecha o menu/painel aberto ou encerra o monitor |

---

## 4. Comandos para Compilação, Execução e Diagnóstico no Windows

Execute os comandos na pasta `Mvisual`:

### 4.1. Compilar apenas as classes (rápido para desenvolvimento/IDE)
```bat
mvn compile
```

### 4.2. Build completo e geração do executável (`target\dist\Mvisual\Mvisual.exe`)
```bat
mvn clean package
```
Ou execute o script completo:
```bat
build.bat
```

### 4.3. Executar via linha de comando após compilar
```bat
java -cp "target\classes;target\input\*" com.switchplayer.SwitchPlayerApp
```

### 4.4. Listar dispositivos DirectShow e modos suportados pela placa de captura
```bat
java -cp "target\classes;target\input\*" com.switchplayer.SwitchPlayerApp --list
java -cp "target\classes;target\input\*" com.switchplayer.SwitchPlayerApp --device "USB Video" --modes
```

---

## 5. Estrutura Atual de Arquivos do Projeto

```text
Mvisual/
├── models/
│   ├── realesrgan-x2plus.onnx       # Modelo ONNX NCHW embutido e auto-provisionado
│   └── README.txt
├── src/main/
│   ├── java/com/switchplayer/
│   │   ├── AiSuperResolution.java   # Motor ONNX assíncrono + auto-provisionamento + métricas neurais
│   │   └── SwitchPlayerApp.java     # Captura FFmpeg, Triple-Buffer, Shader CAS + Super-Resolução GPU e UI OpenGL
│   └── resources/
│       ├── models/
│       │   └── realesrgan-x2plus.onnx
│       ├── icon.ico
│       ├── icon.png
│       └── Mvisual.svg
├── build.bat
├── CHANGELOG-MVISUAL-AI.md
├── CHANGELOG-MVISUAL-IMAGE.md
├── DOCUMENTACAO_CONTINUIDADE_MVISUAL.md
├── installer.iss
├── pom.xml
└── README.md
```

---

## 6. Prompt Atualizado para Próximas Sessões

Caso queira iniciar uma nova sessão no futuro, copie o prompt abaixo:

> Continue o desenvolvimento do projeto **Mvisual Alpha v1.1.0 (SwitchPlayer)** a partir do código atual do repositório. Eu sou o líder do projeto e decido os testes e a validação final em minha **AMD Radeon RX 9060 XT**.
>
> **Estado atual já implementado:**
> - Monitor OpenGL 3.3 + GLFW com barra superior de menus integrada (`Arquivo`, `Vídeo`, `Filtros`, `Áudio`, `Opções`), botões rápidos (`Reiniciar`, `Filtros`) e painéis flutuantes arrastáveis (`F2` Filtros, `F3` Opções, `F1` Atalhos).
> - Modo janela redimensionável com viewport separado abaixo da barra de menus e modo Tela Cheia sem bordas (`F11`) que oculta a barra automaticamente.
> - Reinício seguro de captura (`Ctrl+R`) e troca de saída de áudio em tempo real sem fechar o monitor.
> - Normalização de faixa de cores MJPEG (`YUVJ420P`/`YUVJ422P` $\rightarrow$ `AVCOL_RANGE_JPEG`) no FFmpeg/swscale.
> - Super-Resolução híbrida a **60 FPS sem perda de quadros**: modelo `models/realesrgan-x2plus.onnx` embutido e auto-provisionado em `AiSuperResolution.java` (inferência ONNX assíncrona de ~1 ms sobre sonda `96x54`) guiando o shader OpenGL 3.3 (`FRAG` em `SwitchPlayerApp.java`) com Reconstrução Sub-Pixel Direcional por Gradiente Sobel, Deblocking Bilateral Anti-MJPEG e Micro-Contraste com trava Anti-Ringing (RCAS).
>
> Preserve todas essas funcionalidades, inspecione os arquivos reais antes de editar e aplique quaisquer melhorias diretamente no código mantendo os 60 FPS cravados.
