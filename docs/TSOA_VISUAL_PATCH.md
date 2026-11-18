# Lumen, Beyond Screen e arquivos narrativos

Base da Final Edition `1499f3f`, validada pelo jogador no Honor X8.

## Lumen

O mapa auxiliar usa gradientes em duas escalas para reduzir a influência
da microtextura nas normais. Confiança local, consistência temporal e
luminância limitam a iluminação. O preset Intermediário usa oclusão
ampla e leve. O Ultra também considera um termo curto de contato. Os
realces se apoiam em brilho suave existente e recebem menos peso em
áreas saturadas, de alto contraste ou compatíveis com tons de pele.
Os materiais e a proteção de personagens continuam heurísticos em
cenas 2D já compostas. A análise continua restrita aos quadros reais.

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
