# Gênesis e Lumen no TSOA_FE

A implementação parte do commit estável `1ccdda4`. Os dois módulos atuam na
imagem do jogo e começam desligados. Cada um tem controle independente em
**Configurações → Vídeo**. O controle de intensidade vale para o perfil ativo.

## Preparar uma cena

1. Capture um fundo do jogo com o enquadramento em que pretende jogar. Recorte
   as barras externas do emulador e mantenha as barras desenhadas pelo jogo.
2. Abra **Configurações → Vídeo → Criar perfil de cena** e selecione a captura.
   Marque regiões do ambiente e ajuste profundidade, movimento e reflexividade
   antes de desenhar cada região. Deixe rostos, diálogos e menus fora das áreas
   marcadas.
3. Toque em **Usar este perfil** e confirme. Ative Gênesis, Lumen ou ambos.
   Use a mesma cena e proporção da captura para observar o resultado.

O editor funciona offline no Android System WebView. Seu HTML também abre num
navegador para exportar um `.tscene` importável pelo seletor de arquivos. O
editor reconhece por SHA-256 uma [captura promocional da Steam](https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1672670/ss_17bc9df3256880afd4e94574095044ab4c50ad5b.1920x1080.jpg?t=1791019289)
e sugere uma marcação na janela. A correspondência no jogo ainda depende do
enquadramento e das sobreposições.

Para preparar com mapas em escala de cinza, instale Pillow 9.1 ou superior e
use imagens alinhadas com a referência sRGB opaca.

```sh
python tools/prepare_tsoa_scene.py --reference fundo.png --depth profundidade.png \
  --mask area-permitida.png --motion movimento.png --reflectivity material.png \
  --output cena.tscene
```

Branco na profundidade representa regiões próximas. Preto na máscara protege
o conteúdo. Movimento e reflexividade são opcionais e começam zerados.
O perfil contém a miniatura da referência. Mantenha-o com a sua cópia do jogo.

## Renderização e limites

- Gênesis produz deslocamentos sutis nos planos autorizados, ponderados pela
  profundidade e pelo mapa de movimento. Usa o tempo do conteúdo real como
  fase e verifica a origem e o destino da amostra. Em quadros estáticos,
  mantém a fase e reaproveita a confiança da cena.
- Lumen deriva normais locais do mapa de profundidade. Baixo aplica luz
  direcional. Intermediário acrescenta especular por material e oclusão
  local. Ultra acrescenta até quatro amostras de sombra direcional de contato
  e uma contribuição indireta de cor. Os ajustes são limitados para
  preservar a iluminação desenhada na arte.
- O compositor compara 24 pontos do quadro real com a miniatura numa
  textura de 1×1. Máscara, correspondência local e profundidade reduzem
  interferência nas sobreposições. Em cenas incompatíveis, o passo devolve
  os pixels de entrada. Para proteger uma área crítica, exclua-a ao criar o
  perfil. Sem perfil, com os módulos desligados ou em caso de falha de
  inicialização, o compositor segue pelo caminho habitual.
- A composição vem depois do EASU do FSR, quando ativo, e antes de FXAA,
  nitidez, Bloom e HDR simulado. O Frame Generation recebe os quadros
  processados. Seus quadros sintéticos ficam fora da identificação de cenas.
- O protótipo usa um perfil por vez. As regiões são marcadas manualmente.
  As imagens compostas disponíveis ao Winlator permitem estimar iluminação
  superficial, com limitações de enquadramento e oclusão. Reconstrução
  automática de personagens e geometria, preenchimento de oclusões e
  iluminação volumétrica continuam em aberto.

O formato v1 usa cabeçalho big-endian de 24 bytes (`TSOSCENE`, versão,
dimensões originais e dimensões reduzidas), duas imagens RGBA8 em ordem
de linhas a partir do topo, e SHA-256 de cabeçalho e imagens. A estrutura
usa profundidade, movimento, reflexividade e máscara nos canais R, G, B e A.
A resolução reduzida é limitada a 256 por eixo. O modo offline guarda um
perfil privado com no máximo 524.344 bytes em `noBackupFilesDir`, com troca
atômica. **Remover perfil** libera esse espaço. Os mapas são enviados à GPU
na entrada da sessão e quando o perfil ou contexto muda.

## Validação

Os testes em `tools/tests/test_tsoa_scene_*.py` cobrem o formato, o editor
offline, a troca atômica de perfil e sessão, o fallback e a composição GLES.
Há testes com cenas sintéticas para efeitos independentes e proteção de
regiões, além de comparação local com capturas promocionais. A validação no
Honor X8 ainda é necessária para verificar o WebView, o enquadramento das
cenas reais e o custo dos presets na Adreno 610.
