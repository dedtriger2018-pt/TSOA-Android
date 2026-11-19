# Cinematic Engine · Simulação óptica

Em **Configurações → Vídeo → Cinematic Engine**, selecione Desligado,
Baixo, Intermediário ou Ultra. O preset geral controla a intensidade
e o custo do processamento. As cinco chaves e suas intensidades são
independentes. Ajuste também tamanho do grão e preferência por tons
médios ou sombras. A opção geral continua desligada em instalações
novas. Versões anteriores reaproveitam as chaves de preset, intensidade,
vinheta e granulação quando havia um valor salvo.

- **Anamorphic Lens** cria reflexos horizontais a partir de altas
  luzes com contraste local. A extensão tem queda de intensidade com a
  distância e usa uma tonalidade fria.
- **Adaptive Focus** usa profundidade, contorno e confiança do Genesis.
  Pode requisitar somente a análise se Gênesis e Lumen estiverem desligados.
  Quadros de corte e regiões incertas mantêm a nitidez. O filtro limita
  a amostragem às profundidades vizinhas e protege a faixa de diálogos.
- **Film Halation** produz um halo avermelhado e preserva o centro das
  altas luzes. Quando Bloom está ativo, aproveita sua indicação luminosa
  e reduz a contribuição do halo vermelho.
- **Organic Film Grain** combina duas escalas e adapta o peso à luminância.
  O padrão fica estável na tela para preservar o histórico do Frame Generation.
- **Optical Vignette** aplica queda óptica inscrita no viewport 16:9.
  Beyond Screen recebe a imagem anterior à vinheta e suas bordas passam
  intactas pelo shader.

O Cinematic antigo aplicava vinheta e granulação apenas com as chaves
separadas ligadas. Ambos os valores começavam desligados. O grão tinha
amplitude máxima teórica de cerca de três níveis de 8 bits, antes do
FXAA e da nitidez. O retorno antecipado para alfa inferior a 0,99
também podia deixar a imagem inteira intacta. A nova composição opera
sobre pixels visíveis, preserva o alfa e vem depois dos filtros
espaciais, do HDR e do Beyond Screen. A máscara de interfaces é
heurística quando o texto já está dentro da imagem do jogo.

Os reflexos e o halo compartilham uma textura reduzida de até 192 pixels
no eixo maior e dois passes de extensão. Sem os dois efeitos luminosos,
o compositor evita essa captura. A análise estrutural ocorre somente
nos quadros reais e compartilha os mapas com Genesis e Lumen. Falhas
de compilação ou framebuffer recorrem à composição anterior. O registro
Optics GPU draw inclui intensidades, uso de profundidade, células
alteradas e diferença máxima. A comparação usa o resultado real do
framebuffer no primeiro quadro e em intervalos de cinco segundos.

## Idiomas e testes

A tela dedicada utiliza português brasileiro, inglês ou chinês
simplificado com configuração local ao menu. O editor de controles
chamado pelo launcher usa a mesma seleção para rótulos e opções de
layout. Identificadores de teclas e os registros técnicos preservam os
nomes de origem. A exportação localiza os rótulos de diagnóstico.
O idioma interno do jogo e o locale do Wine continuam configurados
pelas fontes originais.

Os testes GLES comparam os cinco efeitos, os três presets, os controles
do grão, a confiança de foco, as bordas ambientais e o alfa de saída.
Os testes de recursos conferem as mesmas chaves, contagens de itens e
parâmetros de formatação nos idiomas. A aparência e a taxa de quadros no
Honor X8, incluindo a leitura das telas em diferentes proporções,
precisam de validação física.
