# Comissionamento em uma máquina real

Passo a passo para ligar o gateway a um S7-1200 de verdade. É o mesmo caminho do TCC, com o gateway no lugar do
Node-RED.

## 1. No CLP (TIA Portal)

Crie um bloco de dados com o que a máquina já sabe. O programa da máquina não muda: ele só copia para o DB os sinais
que já existem (motor ligado, falha ativa, contador de peças).

| Sinal | Tipo | Exemplo de origem |
|---|---|---|
| `Running` | BOOL | saída do motor principal, ou o bit de ciclo automático |
| `Fault` | BOOL | qualquer alarme que pare a máquina |
| `FaultCode` | INT | número do alarme ativo (0 sem falha) |
| `GoodCount` / `RejectCount` | DINT | contadores de peças boas e refugadas (podem estourar, o sistema trata) |
| analógicos | INT ou REAL | corrente, temperatura, pressão; o valor bruto do cartão (0..27648) pode ir sem escala, o gateway escala |

Depois, conforme o protocolo escolhido:

**OPC UA** (recomendado quando a CPU tem firmware 4.4 ou mais novo):

1. Propriedades da CPU → OPC UA → Servidor → ativar o servidor OPC UA.
2. Verificar a licença de runtime de OPC UA exigida pela CPU.
3. No DB, deixar as variáveis acessíveis pelo HMI/OPC UA.
4. Endereço no gateway: `"NomeDoDB"."Variavel"`, com `namespace-uri: http://www.siemens.com/simatic-s7-opcua`.

**S7 PUT/GET** (qualquer S7-1200; foi o caminho da TV Box no TCC):

1. Propriedades da CPU → Proteção e segurança → Mecanismos de conexão → permitir acesso com PUT/GET.
2. Propriedades do DB → desmarcar "Acesso otimizado ao bloco". Sem isso o DB não tem endereços fixos.
3. Compilar e conferir os offsets na coluna "Offset" do DB.
4. Endereço no gateway: `DB1.DBX0.0`, `DB1.DBW2`, `DB1.DBD4` (com `type: REAL` para números reais). Rack 0, slot 1.

## 2. No computador de borda

Qualquer Linux ao lado da máquina: Raspberry Pi 4, TV Box com Debian/Armbian, PC industrial. Ele precisa enxergar o
CLP na rede da máquina e sair para o broker (só conexão de saída, porta 1883 ou 8883).

1. Instale Docker, ou Java 25 se preferir rodar o jar direto.
2. Copie `gateway/gateway.example.yaml`, ajuste ids, endereços e senhas (as senhas podem vir de variáveis de ambiente).
3. Confira os endereços antes de enviar qualquer coisa:

   ```bash
   docker run --rm -v $PWD/gateway.yaml:/etc/machine-telemetry/gateway.yaml machine-telemetry-gateway --check
   ```

   Cada valor lido aparece na tela. Erros comuns e o que significam:
   - `access denied (0x03)`: PUT/GET não está liberado na CPU.
   - `address out of range (0x05)`: DB otimizado ou menor que o configurado.
   - `the server has no namespace`: namespace-uri errado ou servidor OPC UA desativado.
4. Suba o gateway com `restart: unless-stopped` (ou um serviço do systemd) e um volume para `/data`: é onde fica a fila
   local, que segura os dados enquanto a internet cai.
5. Mantenha o relógio certo (NTP): os horários das paradas são os do gateway.

## 3. No broker

Para cada gateway, uma senha no Mosquitto e duas linhas no `mosquitto/acl`:

```
user gw-<cidade>-01
topic write mt/<site>/#
```

Assim a senha de um gateway só publica para o próprio site.

## 4. Na plataforma

Cadastre a máquina e os textos das falhas:

```bash
curl -X PUT http://<plataforma>:8080/api/v1/machines/ENS-01 -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"site":"joacaba","name":"Ensacadeira 1","idealCycleSeconds":6}'
curl -X PUT http://<plataforma>:8080/api/v1/machines/ENS-01/fault-codes/102 -H "X-API-Key: $KEY" \
  -H "Content-Type: application/json" -d '{"description":"Saco travado na boca de ensaque"}'
```

O `idealCycleSeconds` é o tempo de um ciclo na velocidade nominal (da placa da máquina ou do fabricante). É a
referência da performance no OEE, então vale combinar esse número com quem opera a máquina.

## 5. Máquina sem rede: LoRaWAN

Para um silo ou uma casa de bombas longe da rede, o gateway manda pelo módulo LoRaWAN na USB, como no TCC.

1. No The Things Network: aplicação, dispositivo (no TCC: AU915 FSB 1, LoRaWAN 1.0.3) e um webhook para
   `https://<plataforma>/api/v1/integrations/ttn/uplink`, com o cabeçalho adicional `X-Webhook-Token`.
2. Opcional: cole `lorawan/ttn-formatter.js` como formatador de uplink, para ver os valores no console da TTN.
3. No gateway, `uplink.type: lorawan`, com o comando de envio e as respostas do seu módulo (manual de comandos AT).
   O intervalo mínimo de 6 s vem do TCC: abaixo disso o módulo respondia "busy".
4. Na plataforma, cadastre a máquina com `lorawan: {devEui, port, analogs}` e `staleAfterSeconds` maior que o
   intervalo de envio (o padrão para LoRaWAN é 900 s).

No TCC o enlace ficou estável até cerca de -100 dBm (280 m, sem antena externa). O painel mostra o RSSI de cada
uplink, o que ajuda a decidir onde pôr o gateway LoRaWAN.
