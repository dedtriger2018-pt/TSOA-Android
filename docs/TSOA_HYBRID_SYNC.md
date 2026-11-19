# Reality Bridge e Chronos · Sincronização híbrida

Em **Configurações → Arquivos**, há três modos.

- **Manual** conserva os controles de criação de checkpoints e capturas.
- **Automático** captura alterações reais de saves e abre o PDA no filtro
  automático. Todos os registros manuais continuam acessíveis pelos filtros.
- **Híbrido** captura as mesmas alterações e abre o PDA com ambos os tipos.

O Chronos verifica o conteúdo da pasta de saves antes de iniciar o jogo.
Após a saída normal do processo convidado, o launcher transfere o
bloqueio da sessão ao backup e compara os arquivos por SHA-256,
ignorando logs. O backup é atômico e reaproveita o formato já validado.
Mudanças durante a cópia invalidam a operação. Saídas forçadas, estado
idêntico ao do início e impressões digitais já arquivadas são ignorados.
Nenhum backup existente é apagado para liberar espaço.

O Chronos marca a origem de cada ponto. Um ponto automático fica
independente da linhagem manual, pois o jogador pode ter carregado
outro slot dentro do jogo. A preferência do ramo atual continua sob
controle do jogador. O PDA exibe um registro diretamente a partir do
checkpoint, com seu próprio estado de leitura. A notificação do Android
é opcional, privada e sem detalhes narrativos.

## Limites da fonte

O APK conhece a pasta de saves do Unity e os logs de execução que
já exporta. Os arquivos e relatórios disponíveis nesta revisão não
contêm um formato verificável dos estados, scripts ou comandos
Naninovel específicos do TSOA para mensagens e desbloqueios.
Capítulos, eventos, remetentes, conversas, notícias e documentos
continuam com registro manual por captura real. O sincronizador
não abre scripts futuros nem interpreta saves como eventos de história.
Não exige OCR, internet, leitura contínua ou alterações no jogo.

Os testes automatizados cobrem o bloqueio de sessão, a comparação
por conteúdo, a exclusão dos logs, os formatos manual e automático,
a deduplicação depois de restaurar saves e a recuperação da pasta
anterior. O encerramento real do Wine e a notificação ainda precisam
de teste no Honor X8.
