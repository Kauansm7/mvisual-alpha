# Mvisual Alpha v1.1.0 — Changelog de Implementação (Monitor Integrado, Filtros, FFmpeg e Super-Resolução Híbrida ONNX + GPU 60 FPS)

## 1. Super-Resolução Híbrida por IA (ONNX Runtime + GPU OpenGL 3.3 @ 60 FPS Zero-Drop)
- **Modelo ONNX Embutido e Auto-Provisionado (`models/realesrgan-x2plus.onnx`):**
  - Incluído em `models/realesrgan-x2plus.onnx` e `src/main/resources/models/realesrgan-x2plus.onnx`.
  - Implementado `AiSuperResolution.resolveOrProvisionModel()` que localiza ou reconstrói automaticamente o binário `.onnx` em disco caso a pasta `models/` não exista, eliminando em definitivo a mensagem de `Modelo ONNX ausente`.
- **Arquitetura Híbrida sem Perda de FPS:**
  - Removido o bloqueio/descarte de quadros no `captureLoop` e eliminada a cópia de quadros Full HD/4K pela CPU. O vídeo capturado vai 100% direto para a GPU via `MemoryUtil.memCopy` a **60 FPS constantes**.
  - A thread `ai-super-resolution` do `AiSuperResolution` executa a inferência ONNX Runtime em ~1 ms sobre uma sonda compacta (`96x54` $\rightarrow$ `192x108`), calculando em tempo real os parâmetros neurais adaptativos (`neuralSharpBoost`, `neuralDenoise`, `neuralContrast`).
- **Pipeline de Super-Resolução e Restauração no Shader GPU (`FRAG`):**
  - **Reconstrução Sub-Pixel Direcional por Gradiente Sobel $3\times3$ (estilo FSR / Anime4K):** afina contornos, textos e folhagens borradas pelo upscale de 720p/900p do console.
  - **Deblocking Bilateral Anti-MJPEG:** remove blocos $8\times8$ e *mosquito noise* de placas de captura UVC USB em áreas planas sem borrar bordas.
  - **Micro-Contraste Adaptativo (Clarity) + Clamp Anti-Ringing (RCAS):** realça texturas finas guiado pelos pesos do ONNX sem gerar halos brancos.

## 2. Janela de Monitor com Barra de Menus e Controles Integrados
- Implementado renderizador de interface OpenGL (`GlUiRenderer`) sobre a janela GLFW do monitor com atlas de fonte (`Segoe UI`).
- Adicionada barra superior de menus estilo emulador desktop (`Arquivo`, `Vídeo`, `Filtros`, `Áudio`, `Opções`) e botões de acesso rápido (`Reiniciar` e `Filtros`).
- No modo janela, o viewport OpenGL do vídeo é calculado exatamente na área útil abaixo da barra de menus (`fbH - topBarPx`), preservando a proporção sem sobrepor controles ao vídeo.
- Ao pressionar `F11` (Tela Cheia sem bordas), a barra de menus é automaticamente ocultada e o vídeo ocupa 100% do monitor; pressionar `F11` novamente restaura as bordas e a barra superior.

## 3. Painel de Filtros (`F2`) e Painel de Opções (`F3`) em Tempo Real
- **Painel de Filtros (`F2` ou botão `Filtros`):**
  - Sliders interativos arrastáveis para Nitidez CAS (0–100%), Raio do Filtro (0,50–2,00 px) e Saturação de Cor (0–200%).
  - Checkboxes para Nitidez Adaptativa, Suavização Espacial FXAA e Super-Resolução por IA (ONNX x2).
  - Botões rápidos para Modo de Escala (`Proporção`, `Preencher`, `Esticar`) e botão `Restaurar Valores Padrão`.
- **Painel de Opções e Diagnóstico (`F3`):**
  - Slider de Volume (0–200%), botão Mudo (`M`), botão VSync em tempo real (`glfwSwapInterval`), alternância de Perfil Gráfico, Preferência de GPU, status da captura/IA e botões para Reiniciar Captura ou Voltar ao Launcher.

## 4. Ciclo de Vida da Captura, Reinício Seguro e Troca de Áudio
- Implementado `requestCaptureRestart()` (`Ctrl+R`) para fechar e liberar `SourceDataLine` e `FFmpegFrameGrabber` (`stop()` + `release()`) e reabrir o dispositivo sem encerrar a aplicação.
- Adicionada fila segura `retiredExchanges` para liberar os buffers nativos (`MemoryUtil.memFree`) na thread de renderização quando a resolução da textura mudar.
- Adicionada troca de saída de áudio (`audioRestartRequested`) que reabre apenas a `SourceDataLine` no mixer escolhido sem interromper o vídeo.

## 5. Normalização de Faixa de Cores FFmpeg (`swscale`)
- Implementado `normalizeGrabberColorRange()` que substitui os formatos deprecados `YUVJ422P`/`YUVJ420P` pelos equivalentes canônicos `AV_PIX_FMT_YUV422P`/`AV_PIX_FMT_YUV420P` com `color_range = AVCOL_RANGE_JPEG` e aplica `sws_setColorspaceDetails` no `SwsContext`, além de filtro de log nativo (`installFfmpegLogFilter()`).
