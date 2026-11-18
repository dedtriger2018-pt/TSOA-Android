# Lumen, Beyond Screen e arquivos narrativos

Base da Final Edition `1499f3f`, validada pelo jogador no Honor X8.

## Lumen

O caminho direto de iluminação do APK funcional foi recuperado.
Normais com amostras espaçadas, seleção de realces já presentes na
arte e sombras limitadas por luminância reduzem o relevo artificial.
A confiança atenua os detalhes locais sem zerar a luz em uma cena
reconhecida. Ultra+ aumenta os reflexos aproximados, usando um mapa
auxiliar de até 320 pixels no eixo maior. Os materiais e a proteção de
personagens continuam heurísticos em cenas 2D já compostas.

O código do Smart Patch mantinha as mesmas preferências, o mesmo
registro no compositor e o mesmo uniform levels.y. A regressão
reproduzida estava em 94fa3be, na segunda ponderação
smoothstep(0.45,0.88,map.a) aplicada à luz. Em uma cena nova,
map.a começava em 0,47. A ponderação extra reduzia a resposta a
menos de 1/255 embora a confiança global fosse 255. Na prova GLES, o
Ultra de 1499f3f alterou 28.122 pixels em mais de um nível no
fixture de primeiro quadro. O Smart Patch alterou zero. Retirar só a
segunda ponderação recuperou o efeito. A nova suíte mede os cinco
presets também nessa condição inicial. O guia mantém o diagnóstico
por leitura da GPU. [Gênesis e Lumen](TSOA_GENESIS_LUMEN.md)

## Beyond Screen

**Vídeo → Beyond Screen** oferece Desligado, Borda preta e Blur
ambiental. Instalações que já usavam intensidade positiva migram para
o modo ambiental com a mesma intensidade. Desfoque, brilho, saturação
e intensidade são independentes. O modo ambiental usa duas texturas
RGBA8 reutilizadas de até 112 pixels no eixo maior, desfoca nos dois
eixos e amostra uma expansão suave das bordas reais. A composição
escreve a extensão somente fora do viewport do jogo.

## Chronos e PDA

A linha do tempo do Chronos desenha as relações gravadas entre
checkpoints e destaca a cadeia ativa. O painel do ponto lista capturas
realmente associadas a ele. Os cartões distinguem decisões, missões,
capítulos e acontecimentos classificados pelo jogador. O gesto de
pinça e os controles de navegação funcionam com um mapa que só
redesenha em resposta à interação. A restauração continua exigindo
confirmação e usa o mesmo backup dos saves anteriores.

O PDA exibe as capturas reais em um terminal com filtros, busca por
categoria/data/checkpoint, marcação de ainda não visto e visualizador
com zoom. Só são exibidas imagens que o jogador já guardou e escolheu
abrir. As capturas servem de evidência da tela vista. A origem dos
saves não fornece contatos, avatares, texto pesquisável de conversas,
missões concluídas ou relações narrativas verificadas.

Os menus PT-BR, inglês e chinês simplificado compartilham os mesmos
controles. O modo movimento reduzido suspende as transições animadas.
Os testes GLES verificam sombras limitadas, microtextura, cortes e
blur em dois eixos com o quadro principal intacto. A navegação visual,
o desempenho e a aparência deste patch ainda precisam de teste no
Honor X8.
