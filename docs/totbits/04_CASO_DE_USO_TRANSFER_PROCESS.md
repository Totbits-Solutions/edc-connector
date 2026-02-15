# Caso de Uso 3: transfer.process — Evaluacion de Politicas al Iniciar una Transferencia

## Resumen

Despues de que una negociacion de contrato termina exitosamente (`FINALIZED`), el consumidor tiene un `ContractAgreement`. Pero tener un acuerdo no significa que los datos fluyan automaticamente. El consumidor debe **iniciar una transferencia** explicitamente.

En ese momento, el proveedor **re-evalua la policy del acuerdo** para confirmar que sigue siendo valida. Esto es critico porque entre la firma del contrato y la solicitud de transferencia puede haber pasado tiempo y las condiciones pueden haber cambiado (credenciales revocadas, restricciones temporales expiradas, etc.).

---

## Relacion con los Casos Anteriores

```
Caso 1: catalog.request       → "Puedo VER este asset?"
Caso 2: contract.negotiation   → "Puedo ACORDAR condiciones para usarlo?"
Caso 3: transfer.process       → "Puedo OBTENER los datos AHORA?"    ← este
Caso 4: policy.monitor         → "Sigo pudiendo usarlos?"
```

---

## Maquina de Estados de la Transferencia

```
CONSUMIDOR                                    PROVEEDOR
    |                                              |
    | POST /transferprocesses                      |
    | (con contractId del agreement)               |
    |                                              |
    | INITIAL                                      |
    |   → Preparar data flow (data plane)          |
    |   → REQUESTING ---------------------------->|
    |     (envia TransferRequestMessage)           |
    |                                              | REQUESTED
    |                                              |   → Verificar token JWT
    |                                              |   → Verificar identidad del consumidor
    |                                              |   → RE-EVALUAR policy del agreement ← CLAVE
    |                                              |   → Validar transferType soportado
    |                                              |   → Validar dataDestination
    |                                              |
    |                                              | Si FALLA:
    |                                              |   → TERMINATED
    |                                              |
    |                                              | Si PASA:
    |                                              |   → INITIAL (provider side)
    |                                              |   → Iniciar data flow (data plane)
    |                                              |   → STARTING
    |  STARTED <----------------------------------|   → STARTED
    |    (datos fluyendo)                          |     (datos fluyendo)
    |                                              |
    |  ... transferencia activa ...                |
    |                                              |
    |  COMPLETING <-------------------------------|   → COMPLETING
    |  COMPLETED                                   |   → COMPLETED
```

### Todos los estados

| Estado | Codigo | Descripcion |
|--------|--------|-------------|
| INITIAL | 100 | Recien creado |
| PREPARATION_REQUESTED | 250 | Preparacion del data plane solicitada |
| REQUESTING | 400 | Consumidor enviando peticion al proveedor |
| REQUESTED | 500 | Proveedor recibio la peticion |
| STARTING | 550 | Proveedor iniciando flujo de datos |
| STARTUP_REQUESTED | 570 | Esperando confirmacion del data plane |
| **STARTED** | **600** | **Datos fluyendo activamente** |
| SUSPENDING | 650 | Suspension en progreso |
| SUSPENDED | 700 | Transferencia pausada |
| RESUMING | 720 | Reanudacion en progreso |
| RESUMED | 725 | Transferencia reanudada |
| COMPLETING | 750 | Completando la transferencia |
| **COMPLETED** | **800** | **Estado final — exito** |
| TERMINATING | 825 | Terminando (error o cancelacion) |
| **TERMINATED** | **850** | **Estado final — fallo** |

---

## Donde se Evalua la Policy (Detalle Tecnico)

Cuando el proveedor recibe el `TransferRequestMessage`, ocurren **3 evaluaciones** en secuencia:

### 1. Verificacion del token JWT (scope: `request.transfer.process`)

```
ProtocolTokenValidator.verify()
  → Crea RequestTransferProcessPolicyContext
  → policyEngine.evaluate(agreement.policy, requestContext)
  → Extrae scopes necesarios
  → Verifica JWT del consumidor con esos scopes
  → Devuelve ParticipantAgent con claims
```

Este paso verifica que el mensaje DSP entrante es autentico y esta autorizado.

### 2. Re-evaluacion de la policy del acuerdo (scope: `transfer.process`)

```
ContractValidationService.validateAgreement(agent, agreement)
  │
  ├─ Verificar identidad: agent.identity == agreement.consumerId?
  │   → Si no coincide: "Invalid provider credentials" → RECHAZAR
  │
  └─ Evaluar policy con contexto temporal:
      var policyContext = new TransferProcessPolicyContext(
          agent,          // Claims del consumidor
          agreement,      // Acuerdo firmado
          Instant.now()   // Momento actual ← CLAVE para restricciones temporales
      );
      policyEngine.evaluate(agreement.getPolicy(), policyContext)
      → Si falla: "Policy does not fulfill the agreement" → RECHAZAR
```

### 3. Validaciones adicionales

```
  → Validar dataDestination (para PUSH)
  → Verificar que transferType es soportado por el data plane
  → Verificar que el asset existe
```

### Codigo fuente

Archivo: `core/control-plane/control-plane-contract/src/.../ContractValidationServiceImpl.java`

```java
public Result<ContractAgreement> validateAgreement(ParticipantAgent agent, ContractAgreement agreement) {
    // Verificar que la identidad del agente coincide con el consumidor del acuerdo
    if (!Objects.equals(agent.getIdentity(), agreement.getConsumerId())) {
        return failure("Invalid provider credentials");
    }

    // Crear contexto con el momento actual
    var policyContext = new TransferProcessPolicyContext(agent, agreement, Instant.now());

    // Re-evaluar la policy del acuerdo
    var policyResult = policyEngine.evaluate(agreement.getPolicy(), policyContext);
    if (!policyResult.succeeded()) {
        return failure(format("Policy does not fulfill the agreement %s, policy evaluation %s",
            agreement.getId(), policyResult.getFailureDetail()));
    }
    return success(agreement);
}
```

### TransferProcessPolicyContext — que datos tiene disponibles

Archivo: `spi/control-plane/contract-spi/src/.../TransferProcessPolicyContext.java`

```java
public class TransferProcessPolicyContext extends PolicyContextImpl
        implements AgreementPolicyContext, ParticipantAgentPolicyContext {

    public static final String TRANSFER_SCOPE = "transfer.process";

    // Datos disponibles para las policy functions:
    ParticipantAgent participantAgent()     // Claims/credenciales del consumidor
    ContractAgreement contractAgreement()   // Acuerdo firmado (fecha, ids, policy)
    Instant now()                           // Momento actual de la evaluacion
    String scope()                          // "transfer.process"
}
```

---

## Ejemplo Practico con Peticiones HTTP

### Escenario

Continuando del caso 2: el consumidor ya tiene un `ContractAgreement` con id `agreement-uuid-456` para el asset `dataset-publico`. Ahora quiere recibir los datos.

### Paso 1: Iniciar la transferencia (consumidor)

```http
POST http://consumidor:8181/api/management/v3/transferprocesses
Content-Type: application/json
```

**Transferencia PUSH (el proveedor envia datos al consumidor):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "TransferRequest",
  "protocol": "dataspace-protocol-http",
  "counterPartyAddress": "http://proveedor:8282/api/dsp",
  "contractId": "agreement-uuid-456",
  "transferType": "HttpData-PUSH",
  "dataDestination": {
    "@type": "DataAddress",
    "type": "HttpData",
    "baseUrl": "http://consumidor:9999/api/receive-data"
  },
  "callbackAddresses": [
    {
      "transactional": false,
      "uri": "http://consumidor:8181/api/callbacks",
      "events": ["transfer.process.started", "transfer.process.completed", "transfer.process.terminated"]
    }
  ]
}
```

**Transferencia PULL (el consumidor obtiene un endpoint para leer datos):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "TransferRequest",
  "protocol": "dataspace-protocol-http",
  "counterPartyAddress": "http://proveedor:8282/api/dsp",
  "contractId": "agreement-uuid-456",
  "transferType": "HttpData-PULL",
  "callbackAddresses": [
    {
      "transactional": false,
      "uri": "http://consumidor:8181/api/callbacks",
      "events": ["transfer.process.started", "transfer.process.completed"]
    }
  ]
}
```

**Respuesta:**
```json
{
  "@type": "IdResponse",
  "@id": "transfer-uuid-789",
  "createdAt": 1707900000000
}
```

### Paso 2: Consultar el estado

```http
GET http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid-789/state
```

**Durante la solicitud:**
```json
{
  "@type": "TransferState",
  "state": "REQUESTING"
}
```

**Cuando los datos estan fluyendo:**
```json
{
  "@type": "TransferState",
  "state": "STARTED"
}
```

**Si la policy fallo en la re-evaluacion:**
```json
{
  "@type": "TransferState",
  "state": "TERMINATED"
}
```

### Paso 3: Ver detalles completos de la transferencia

```http
GET http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid-789
```

```json
{
  "@type": "TransferProcess",
  "@id": "transfer-uuid-789",
  "type": "CONSUMER",
  "state": "STARTED",
  "stateTimestamp": 1707900005000,
  "protocol": "dataspace-protocol-http",
  "counterPartyAddress": "http://proveedor:8282/api/dsp",
  "contractAgreementId": "agreement-uuid-456",
  "transferType": "HttpData-PUSH",
  "dataDestination": {
    "type": "HttpData",
    "baseUrl": "http://consumidor:9999/api/receive-data"
  },
  "callbackAddresses": [
    {
      "uri": "http://consumidor:8181/api/callbacks",
      "events": ["transfer.process.started", "transfer.process.completed", "transfer.process.terminated"],
      "transactional": false
    }
  ]
}
```

### Paso 4: Suspender una transferencia activa

```http
POST http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid-789/suspend
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "SuspendTransfer",
  "reason": "Mantenimiento programado del sistema receptor"
}
```

### Paso 5: Reanudar la transferencia

```http
POST http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid-789/resume
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" }
}
```

### Paso 6: Terminar una transferencia

```http
POST http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid-789/terminate
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "TerminateTransfer",
  "reason": "Ya no necesitamos estos datos"
}
```

---

## Escenarios de Evaluacion de Policy

### Escenario A: Transferencia exitosa

```
Consumidor solicita transferencia con agreement valido
  → Token JWT: VALIDO
  → Identidad: consumidor-id == agreement.consumerId ✓
  → Policy temporal: Instant.now() dentro del rango permitido ✓
  → DataDestination: valida ✓
  → Resultado: STARTED (datos fluyendo)
```

### Escenario B: Policy temporal expirada

```
El contrato se firmo hace 60 dias, pero la policy dice "maximo 30 dias"
  → Token JWT: VALIDO
  → Identidad: coincide ✓
  → Policy temporal:
      constraint: inForceDate lteq contractAgreement+30d
      Instant.now() > contractSigningDate + 30 dias
      → FALLA
  → Resultado: TERMINATED ("Policy does not fulfill the agreement")
```

### Escenario C: Credenciales revocadas

```
El consumidor firmo el contrato pero sus credenciales fueron revocadas despues
  → Token JWT: INVALIDO (credencial revocada en el Identity Hub)
  → La verificacion del IdentityService falla ANTES de evaluar la policy
  → Resultado: TERMINATED
```

### Escenario D: Identidad incorrecta

```
Un tercero intenta usar un agreementId que no le pertenece
  → Token JWT: VALIDO (para su identidad)
  → Identidad: tercero-id != agreement.consumerId
  → FALLA: "Invalid provider credentials"
  → Resultado: TERMINATED
```

### Escenario E: TransferType no soportado

```
Consumidor pide transferencia con transferType "Kafka-PUSH"
pero el data plane del proveedor solo soporta "HttpData-PUSH"
  → Token JWT: VALIDO
  → Identidad: coincide ✓
  → Policy: PASA ✓
  → TransferType check: FALLA (no soportado)
  → Resultado: TERMINATED
```

---

## PUSH vs PULL — Como Afecta a la Transferencia

| Aspecto | PUSH | PULL |
|---------|------|------|
| Quien inicia el flujo de datos | El proveedor | El consumidor |
| dataDestination requerido | SI (donde enviar los datos) | NO (se recibe un endpoint) |
| Ejemplo de transferType | `HttpData-PUSH` | `HttpData-PULL` |
| Caso de uso tipico | Enviar fichero a endpoint del consumidor | El consumidor accede a un API con token temporal |

### Flujo PUSH
```
Consumidor → "Envia los datos a http://mi-servidor:9999/receive"
Proveedor → Data Plane → HTTP POST a http://mi-servidor:9999/receive
```

### Flujo PULL
```
Consumidor → "Quiero acceder a los datos"
Proveedor → Genera endpoint temporal + token
Consumidor ← Recibe DataAddress con URL y credenciales
Consumidor → HTTP GET a la URL temporal con el token
```

---

## Rol del Data Plane

La policy se pasa al data plane en dos momentos clave:

| Momento | Metodo | Quien | Que hace |
|---------|--------|-------|----------|
| Consumidor INITIAL | `dataFlowController.prepare(process, policy)` | Consumidor | Prepara el canal de datos |
| Proveedor INITIAL | `dataFlowController.start(process, policy)` | Proveedor | Inicia el flujo de datos |

El `DataFlowController` recibe la policy y puede aplicar restricciones adicionales a nivel de data plane (por ejemplo, limitar ancho de banda, filtrar campos, etc.).

---

## Scopes de Policy en la Transferencia

| Scope | Clase | Cuando | Que evalua |
|-------|-------|--------|-----------|
| `request.transfer.process` | `RequestTransferProcessPolicyContext` | Cada mensaje DSP entrante | Autorizar el mensaje del protocolo |
| `transfer.process` | `TransferProcessPolicyContext` | Al recibir la peticion de transferencia | Re-evaluar la policy del agreement con datos actuales |

---

## Archivos Clave en el Repositorio

| Archivo | Rol |
|---------|-----|
| `core/control-plane/control-plane-transfer-manager/src/.../TransferProcessManagerImpl.java` | Maquina de estados completa |
| `core/control-plane/control-plane-aggregate-services/src/.../TransferProcessProtocolServiceImpl.java` | Manejo de mensajes DSP de transferencia |
| `core/control-plane/control-plane-contract/src/.../ContractValidationServiceImpl.java` | `validateAgreement()` — re-evaluacion de policy |
| `spi/control-plane/contract-spi/src/.../TransferProcessPolicyContext.java` | Contexto con `now()`, `agreement`, `agent` |
| `spi/control-plane/transfer-spi/src/.../TransferProcessStates.java` | Definicion de estados |
| `spi/control-plane/transfer-spi/src/.../TransferRequest.java` | Modelo de la peticion |
| `extensions/control-plane/api/management-api/transfer-process-api/` | Endpoints REST |
