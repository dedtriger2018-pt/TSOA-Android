# Gênesis e Lumen automáticos

Em **Configurações → Vídeo**, escolha Desligado, Baixo, Intermediário ou Ultra
para cada motor. Ultra+ fica disponível para o Lumen e inicie o jogo. As preferências do APK anterior são
reaproveitadas. Os efeitos vêm desligados em novas instalações.

## Dados e reconstrução

O editor, a importação e o formato de perfil manual foram removidos. A fonte
é o quadro real da janela do jogo. O renderizador X11 recebe texturas de
`Drawable` do Wine. A composição do Unity já chegou a essas texturas como
imagem 2D. Personagens, geometria, materiais e HUD do Unity não chegam como
camadas separadas. O importador valida os arquivos do Unity, mas não fornece
uma correspondência entre assets originais e o quadro em execução. A
implementação trabalha com essa imagem disponível, sem acessar arquivos do
jogo durante a análise.

- Uma captura reduzida, de até 192 pixels no eixo maior, alimenta ambos os
  motores. Gradientes, contraste regional e posição produzem estimativas de
  relevo e contornos. Os canais auxiliares guardam tendência especular e
  estabilidade local. O Lumen calcula normais do gradiente de relevo.
- O Lumen usa amostras mais espaçadas do mapa estrutural para suavizar
  normais e prefere realces suaves já presentes na arte. Ultra+ acrescenta
  dois mapas temporais de até 320 pixels por eixo para reflexos aproximados.
- O reconhecimento amostra 40 regiões por quadro real. Mudanças globais
  suspendem a reconstrução e fazem a próxima análise substituir o cache. Em
  regiões estáveis, os mapas anteriores reduzem variações de relevo e
  material. O cache estrutural retém os mapas corrente e anterior em até 576 KiB.
  Ultra+ pode acrescentar até 800 KiB para seus dois mapas de reflexão. Repetições do mesmo quadro reaproveitam o cache. A
  análise aquece em intervalos de 48 ms e depois usa intervalos de 88 a
  140 ms, conforme o preset mais alto dos dois motores.
- Gênesis desloca amostras de acordo com o relevo. A comparação com o
  quadro real protege contornos, regiões em transição e o destino dos
  deslocamentos. Lumen estima a posição da luz a partir das áreas mais
  claras e usa a orientação das superfícies para reconstruir a resposta
  luminosa. Intermediário acrescenta especular seletivo e oclusão ampla e
  discreta. Ultra acrescenta um termo breve de contato. Todos os presets
  protegem sombras já presentes com limites dependentes da luminância.
- A máscara protege bordas, área de diálogos, detalhes de alto contraste
  e regiões com discrepância temporal. Tons quentes típicos de pele
  reduzem a intensidade. A proteção é heurística. Textos ou personagens
  já compostos com o fundo, especialmente áreas lisas e estáticas, podem
  receber parte do efeito.
- A imagem entra nessa etapa depois do EASU, quando FSR está ativo, e
  segue para FXAA, nitidez, Bloom e HDR simulado. Frame Generation recebe
  o resultado apenas ao capturar um quadro real. Seus quadros sintéticos
  seguem direto para apresentação. O cursor do emulador é composto à parte.

Profundidade e materiais são inferidos do quadro 2D. O resultado é uma
aproximação de relevo, sem profundidade geométrica métrica ou identificação
semântica garantida. Cenas planas, pouco informativas ou com mudanças fortes
podem manter a imagem original. Falhas de shader ou framebuffer também
devolvem o controle ao caminho de renderização normal.

## Diagnóstico do pipeline gráfico

O APK `91ab403` podia desenhar um quadro idêntico mesmo com os shaders
executados. O reconhecimento exigia que uma proporção alta de pontos
coincidisse com a referência importada, e o shader rejeitava ainda mais
regiões quando a cor local divergia. Essa condição foi removida ao passar
a construir a referência temporal a partir dos próprios quadros. Sem
diagnóstico daquela sessão no Honor, a causa exata do sintoma continua
indeterminada.

O visor `TSOA_FE_DIAG` e o registro de diagnóstico do launcher recebem
`Scene GPU draw` no primeiro quadro e a cada cinco segundos de quadros
reais. Um shader compara a textura de entrada com a de saída e escreve uma
amostra de 8×4 para leitura de 128 bytes. O log inclui presets (`g` e `l`),
`analyses`, `confidence8`, `cut8`, `changedCells` e `maxDelta8`. O campo reflections informa o tamanho real do mapa Ultra+. Um valor
positivo de `changedCells` prova uma alteração maior que 1/255 em pelo menos
uma das regiões amostradas, antes do Bloom e HDR. `Scene GPU bypassed`
identifica falhas. A leitura da GPU é periódica para evitar sincronização
por quadro.

```sh
adb logcat -d -s TSOA_FE_DIAG:I | grep -E 'Scene GPU|Scene reconstruction'
```

O teste GLES `tools/tests/test_tsoa_scene_gpu.py` compila os shaders
de produção, compara pixels, mede as saídas em todos os presets, verifica
áreas protegidas e confirma recuperação automática após um corte. O teste
de desempenho e a inspeção visual no Honor X8 dependem do aparelho.
