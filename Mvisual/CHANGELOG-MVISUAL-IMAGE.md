# Mvisual — alterações de imagem (build de trabalho)

## Implementado nesta rodada
- Controle de saturação no launcher (0–200%).
- Opção de suavização espacial experimental de bordas.
- Opções de escala: preservar proporção, preencher com crop central e esticar.
- Atalhos `[` e `]` para reduzir/aumentar saturação durante a exibição.
- Documentação das limitações de 1080p→4K e da diferença entre filtros e IA.

## Ainda não implementado
- IA neural real de super-resolução/restauração.
- Barra superior integrada à janela do monitor com botões Reiniciar/Filtros.
- Detecção/seleção efetiva de backend de inferência AMD/NVIDIA.
- Interpolação de quadros.
- Build completo e testes em hardware Windows nesta sessão.

## Observação
A suavização é um filtro espacial simples e experimental, não uma implementação oficial de FXAA e não IA.
