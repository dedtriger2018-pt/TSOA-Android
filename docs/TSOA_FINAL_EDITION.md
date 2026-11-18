# TSOA_FE Final Edition

A base é `e65ba84`. Gênesis e Lumen funcionaram no teste físico do
jogador no Honor X8. A edição mantém esses shaders e a análise automática
de quadros reais. As opções novas começam desligadas.

## Uso

- **Vídeo** reúne Modo Original, resolução, limite de quadros, FSR/FXAA/CAS,
  Bloom, HDR simulado, Frame Generation, níveis de Gênesis, Lumen e
  Cinematic, Beyond Screen, qualidade adaptativa e painel de desempenho.
  Modo Original suspende os efeitos, o painel e a integração opcional,
  recuperando os valores salvos ao sair do modo. Resolução e controles
  configurados continuam disponíveis no modo.
- **Cinematic Engine** usa um histograma aproximado de três regiões de
  luminância, média e temperatura de cor. O GPU atualiza uma textura de
  1×1 em intervalos de 130–260 ms de conteúdo novo, com amortecimento
  temporal. Exposição, contraste e cor são limitados para proteger pretos
  e altas luzes. Granulação fixa e vinheta são opcionais. Se Bloom estiver
  ativo, ele recebe a imagem ajustada.
- **Beyond Screen** oferece Desligado, Borda preta e Blur ambiental.
  O modo ambiental reduz o viewport, desfoca em dois eixos e usa as
  bordas reais da cena, com intensidade, brilho, desfoque e saturação
  reguláveis. O teste GLES compara os pixels internos bit a bit.
  Stretch ocupa o viewport e deixa o efeito sem área para preencher.
- **Painel de desempenho** exibe cadência de conteúdo do compositor,
  quadros sintéticos submetidos, tempo de submissão CPU/driver, PSS do
  processo e estado térmico reportado pelo Android. Esses números
  representam os pontos de medição do compositor. O painel não mede
  apresentação física ou tempo de execução na GPU. Com qualidade
  adaptativa, janelas de dois segundos e histerese limitam os presets
  de Gênesis, Lumen, Cinematic e Beyond. As preferências ficam salvas.
- **Arquivos → PDA nativo** adiciona o botão **PDA** na tela do jogo.
  Abra a mensagem, documento, notícia, diálogo, escolha ou missão dentro
  do jogo. Use **Guardar quadro do jogo** e escolha a categoria. O Android
  copia a superfície do jogo por PixelCopy e registra apenas o quadro
  visível. O arquivo pode ser revisto no jogo ou em **Consultar PDA**.
- **Arquivos → Chronos** cria checkpoints depois que o jogo foi fechado.
  O mapa permite arrastar, ampliar e consultar os ramos reais. Selecione
  um ponto para ver seus registros ou gerenciar a restauração. Restaurar
  um checkpoint exige confirmação e conserva a pasta anterior como
  backup. Cada captura do PDA guarda o checkpoint ativo no momento.
- **Consultar PDA** permite filtrar categorias e buscar por data,
  categoria ou checkpoint. As imagens começam fechadas. O marcador de
  não visto acompanha a abertura da captura no arquivo local.

## Dados e proteção dos saves

O caminho verificado é `AppData/LocalLow/Caramel-Mocha Studio/TSOA_FE`.
Chronos usa o exportador e restaurador existente, verifica arquivos
durante a criação e trabalha sobre a árvore inteira de saves, sem
logs. A sessão dedicada e as operações sobre saves são mutuamente
exclusivas dentro do launcher. Os checkpoints ficam em
`noBackupFilesDir/chronos`, limitados a 64 arquivos e 512 MiB compactados.
O PDA fica em `noBackupFilesDir/pda`, com até 96 imagens e 128 MiB.
A exportação existente em ZIP cobre a pasta de saves em uso. A reinstalação
com assinatura diferente apaga o armazenamento privado se exigir a
desinstalação. Exporte os saves antes dessa reinstalação.

A ponte narrativa disponível é de leitura da superfície renderizada.
Os dados inspecionados não oferecem um contrato verificado de mensagens,
missões, flags, notificações ou eventos do Naninovel específico do TSOA.
Por isso, o registro de missões é um arquivo de telas realmente abertas
pelo jogador, e a árvore liga estados reais salvos. O jogo controla o
desbloqueio das telas. Decodificação automática de missões, árvore
semântica, notificações e vibrações narrativas seguem pendentes.
Os arquivos do jogo e o registro Wine ficam fora dessas operações. A
abrangência de estados eventualmente guardados no registro Wine ainda
depende de validação com saves reais.

## Revisão das 17 seções

| Seção | Estado |
|---|---|
| 1–2. Base e preservação | Base validada, lobby animado existente, recursos e correções de runtime preservados. |
| 3. Framework | O compositor reutiliza alvos e encadeia EASU, reconstrução, Cinematic, efeitos espaciais e de cor, e Beyond. FG recebe só quadros reais processados. |
| 4–5. Gênesis e Lumen | Preservados conforme o teste físico. Profundidade e materiais estimados da imagem 2D. Decomposição semântica, geometria métrica, oclusões reais, personagens deformáveis e volumetria seguem experimentais ou pendentes. |
| 6–7. Apresentação | Histograma e adaptação de cor na GPU, com extensão ambiental desfocada opcional. Narrativa e amortecimento temporal próprio das bordas seguem pendentes. |
| 8. Reality Bridge | Captura direta do quadro consultado pelo jogador, PDA categorizado e registro local. Integração semântica pendente. |
| 9. Chronos | Checkpoints, ramos, recuperação dos saves anteriores, vínculos com o PDA. Árvore semântica pendente. |
| 10–12. Desempenho e opções | Painel local, limites adaptativos com histerese, menus PT/EN/zh-CN e Modo Original. |
| 13–14. Modularidade e integridade | Shaders e orçamento gráfico separados da leitura do jogo e dos saves. Mesmo pacote Android, sem dependência online no APK. |
| 15. Validação | GLES para shaders e limites de viewport, testes Java para saves, ramificações e orçamento gráfico, suítes de regressão e build NDK/Gradle pelo Actions. PDA, desempenho, saves da instalação real e instalação limpa da Final Edition precisam de validação física. |
| 16–17. Consolidação e publicação | Código, documentação e testes versionados. APK produzido a partir do commit da execução do Actions. |
