# Caso de Uso 4: policy.monitor — Vigilancia Continua de Politicas en Transferencias Activas

## Resumen

Los 3 casos anteriores evaluan politicas en un **momento puntual** (al pedir catalogo, al negociar, al iniciar transferencia). Pero hay transferencias que **no terminan** — streaming de datos en tiempo real, APIs con acceso continuo, etc. Para estas, el EDC necesita **vigilar continuamente** que las politicas se sigan cumpliendo.

El **Policy Monitor** es un proceso en segundo plano que periodicamente re-evalua las politicas de todas las transferencias activas. Si una politica deja de cumplirse, **termina automaticamente la transferencia**.

---

## Relacion con los Casos Anteriores

```
Caso 1: catalog.request       → "Puedo VER este asset?"          (puntual)
Caso 2: contract.negotiation   → "Puedo ACORDAR condiciones?"     (puntual)
Caso 3: transfer.process       → "Puedo OBTENER datos AHORA?"     (puntual)
Caso 4: policy.monitor         → "SIGO pudiendo usar los datos?"  (continuo) ← este
```

La diferencia fundamental: los casos 1-3 son **evaluaciones puntuales** (una vez). El caso 4 es una **evaluacion ciclica** que se repite mientras la transferencia este activa.

---

## Como Funciona Internamente

### Activacion Automatica

El Policy Monitor **no se activa manualmente**. Se dispara automaticamente cuando una transferencia entra en estado `STARTED`, pero **solo en el lado del proveedor**:

```java
// StartMonitoring.java - Event Subscriber
public <E extends Event> void on(EventEnvelope<E> event) {
    if (event.getPayload() instanceof TransferProcessStarted started
        && PROVIDER.name().equals(started.getType())) {
        manager.startMonitoring(started.getTransferProcessId(), started.getContractId());
    }
}
```

**Solo PROVIDER**: El proveedor es responsable de hacer cumplir sus propias politicas. El consumidor no monitorea.

### Ciclo de Vigilancia

```
┌─────────────────────────────────────────────────────────────┐
│                  POLICY MONITOR (loop continuo)              │
│                                                              │
│  Cada N milisegundos:                                        │
│                                                              │
│  1. Tomar batch de PolicyMonitorEntry en estado STARTED      │
│     (con lease para evitar procesamiento concurrente)        │
│                                                              │
│  2. Para cada entry:                                         │
│     ┌──────────────────────────────────────────────────┐     │
│     │ a) Buscar TransferProcess por ID                 │     │
│     │    → No existe? → entry → FAILED                 │     │
│     │                                                  │     │
│     │ b) Transfer ya COMPLETING/COMPLETED/TERMINATED?  │     │
│     │    → Si: entry → COMPLETED (dejar de monitorear) │     │
│     │                                                  │     │
│     │ c) Buscar ContractAgreement por contractId       │     │
│     │    → No existe? → entry → FAILED                 │     │
│     │                                                  │     │
│     │ d) Evaluar policy con PolicyEngine               │     │
│     │    context = PolicyMonitorContext(now(), agreement)│    │
│     │                                                  │     │
│     │ e) Policy PASA?                                  │     │
│     │    → Actualizar timestamp, liberar lease          │     │
│     │    → Entry sigue en STARTED (se re-evalua luego) │     │
│     │                                                  │     │
│     │ f) Policy FALLA?                                 │     │
│     │    → Terminar TransferProcess automaticamente    │     │
│     │    → entry → COMPLETED                           │     │
│     └──────────────────────────────────────────────────┘     │
│                                                              │
│  3. Esperar intervalo configurado                            │
│  4. Repetir                                                  │
└─────────────────────────────────────────────────────────────┘
```

### Codigo fuente del ciclo

Archivo: `core/policy-monitor/policy-monitor-core/src/.../PolicyMonitorManagerImpl.java`

```java
private boolean processMonitoring(PolicyMonitorEntry entry) {
    // 1. Verificar que la transferencia existe
    var transferProcess = transferProcessService.findById(entry.getId());
    if (transferProcess == null) {
        entry.transitionToFailed("TransferProcess %s does not exist");
        update(entry);
        return true;
    }

    // 2. Si la transferencia ya termino, dejar de monitorear
    if (transferProcess.getState() >= TransferProcessStates.COMPLETING.code()) {
        entry.transitionToCompleted();
        update(entry);
        return true;
    }

    // 3. Obtener el contrato
    var contractAgreement = contractAgreementService.findById(entry.getContractId());
    if (contractAgreement == null) {
        entry.transitionToFailed("ContractAgreement %s does not exist");
        update(entry);
        return true;
    }

    // 4. Evaluar la policy CON EL MOMENTO ACTUAL
    var policy = contractAgreement.getPolicy();
    var policyContext = new PolicyMonitorContext(Instant.now(clock), contractAgreement);
    var result = policyEngine.evaluate(policy, policyContext);

    // 5. Si falla → terminar la transferencia
    if (result.failed()) {
        var command = new TerminateTransferCommand(entry.getId(), result.getFailureDetail());
        var terminationResult = transferProcessService.terminate(command);
        if (terminationResult.succeeded()) {
            entry.transitionToCompleted();
            update(entry);
            return true;
        }
    }

    // 6. Si pasa → actualizar timestamp y seguir monitoreando
    entry.updateStateTimestamp();
    return false;
}
```

---

## Estados del Policy Monitor

El PolicyMonitorEntry tiene solo 3 estados:

| Estado | Codigo | Significado |
|--------|--------|-------------|
| **STARTED** | 100 | Monitoreando activamente — se re-evalua en cada ciclo |
| **COMPLETED** | 200 | Monitoreo terminado (transferencia finalizo o fue terminada por policy) |
| **FAILED** | 300 | Error (transferencia o contrato no encontrados) |

```
STARTED ──→ COMPLETED  (transferencia termino normalmente o policy fallo y se termino)
STARTED ──→ FAILED     (transferencia o contrato desaparecieron)
```

---

## PolicyMonitorContext — Que Datos Tiene

Archivo: `spi/policy-monitor/policy-monitor-spi/src/.../PolicyMonitorContext.java`

```java
public class PolicyMonitorContext extends PolicyContextImpl implements AgreementPolicyContext {

    public static final String POLICY_MONITOR_SCOPE = "policy.monitor";

    Instant now()                           // Momento actual de la evaluacion
    ContractAgreement contractAgreement()   // Acuerdo (con fecha de firma, policy, ids)
    String scope()                          // "policy.monitor"
}
```

**Diferencia con `TransferProcessPolicyContext` (caso 3):** el monitor NO tiene acceso al `ParticipantAgent`. Solo tiene el `ContractAgreement` y el momento actual. Esto es porque el monitor evalua **restricciones temporales**, no credenciales del participante.

---

## La Funcion Clave: ContractExpiryCheckFunction

Esta es la unica funcion registrada por defecto en el scope `policy.monitor`. Evalua restricciones temporales con el operando `edc:inForceDate`.

Archivo: `core/control-plane/lib/control-plane-policies-lib/src/.../ContractExpiryCheckFunction.java`

### Dos formatos soportados

**Formato 1: Fecha fija (ISO-8601)**
```json
{
  "leftOperand": "edc:inForceDate",
  "operator": "LEQ",
  "rightOperand": "2025-12-31T23:59:59Z"
}
```
Significado: "la fecha actual debe ser menor o igual al 31 de diciembre de 2025"

**Formato 2: Relativo a la firma del contrato**
```json
{
  "leftOperand": "edc:inForceDate",
  "operator": "LEQ",
  "rightOperand": "contractAgreement+30d"
}
```
Significado: "la fecha actual debe ser menor o igual a 30 dias despues de la firma"

### Sintaxis de expresiones relativas

Formato: `contractAgreement+<numero><unidad>`

| Unidad | Significado | Ejemplo |
|--------|-------------|---------|
| `s` | Segundos | `contractAgreement+3600s` |
| `m` | Minutos | `contractAgreement+60m` |
| `h` | Horas | `contractAgreement+24h` |
| `d` | Dias | `contractAgreement+365d` |

Valores negativos permitidos: `contractAgreement+-5m` = 5 minutos ANTES de la firma.

### Operadores soportados

| Operador | Significado |
|----------|-------------|
| `EQ` | Exactamente igual |
| `NEQ` | No igual |
| `GT` | Mayor que |
| `GEQ` | Mayor o igual |
| `LT` | Menor que |
| `LEQ` | Menor o igual |

`IN` no esta soportado.

---

## Ejemplo Practico

### Escenario

El proveedor ofrece un dataset de streaming con una policy que dice: "el acceso es valido durante 7 dias desde la firma del contrato". Despues de 7 dias, el Policy Monitor detecta la expiracion y termina la transferencia automaticamente.

### Configuracion del Proveedor

**Policy con expiracion a 7 dias:**
```http
POST http://proveedor:8181/api/management/v3/policydefinitions
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-contrato-7-dias",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": [
          {
            "leftOperand": "edc:inForceDate",
            "operator": "gteq",
            "rightOperand": "contractAgreement+0s"
          },
          {
            "leftOperand": "edc:inForceDate",
            "operator": "lteq",
            "rightOperand": "contractAgreement+7d"
          }
        ]
      }
    ]
  }
}
```

Esta policy tiene **dos constraints** que juntas definen una ventana temporal:
- `gteq contractAgreement+0s` → "desde el momento de la firma"
- `lteq contractAgreement+7d` → "hasta 7 dias despues de la firma"

**ContractDefinition usando esta policy:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractDefinition",
  "@id": "contrato-streaming-temporal",
  "accessPolicyId": "policy-acceso-abierto",
  "contractPolicyId": "policy-contrato-7-dias",
  "assetsSelector": [
    {
      "@type": "Criterion",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "dataset-streaming-iot"
    }
  ]
}
```

### Timeline del Escenario

```
Dia 0: Negociacion + ContractAgreement firmado
       contractSigningDate = 2025-02-14T10:00:00Z

Dia 0: Consumidor inicia transferencia (PULL)
       → Caso 3: policy evaluada → PASA (dentro de ventana)
       → Transferencia STARTED
       → Policy Monitor empieza a vigilar (evento TransferProcessStarted)

Dia 1: Policy Monitor evalua
       → now = 2025-02-15T10:00:00Z
       → gteq contractAgreement+0s → 2025-02-15 >= 2025-02-14 → PASA ✓
       → lteq contractAgreement+7d → 2025-02-15 <= 2025-02-21 → PASA ✓
       → Transferencia sigue activa

Dia 3: Policy Monitor evalua
       → now = 2025-02-17T10:00:00Z
       → gteq → PASA ✓
       → lteq → 2025-02-17 <= 2025-02-21 → PASA ✓
       → Transferencia sigue activa

Dia 7: Policy Monitor evalua
       → now = 2025-02-21T10:00:00Z
       → gteq → PASA ✓
       → lteq → 2025-02-21 <= 2025-02-21 → PASA ✓ (justo en el limite)
       → Transferencia sigue activa

Dia 8: Policy Monitor evalua
       → now = 2025-02-22T10:00:00Z
       → gteq → PASA ✓
       → lteq → 2025-02-22 <= 2025-02-21 → FALLA ✗
       → PolicyEngine devuelve failure
       → TerminateTransferCommand enviado
       → Transferencia → TERMINATED
       → PolicyMonitorEntry → COMPLETED
       → Datos dejan de fluir
```

### Peticiones HTTP del Consumidor

**Iniciar transferencia (dia 0):**
```http
POST http://consumidor:8181/api/management/v3/transferprocesses
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "TransferRequest",
  "protocol": "dataspace-protocol-http",
  "counterPartyAddress": "http://proveedor:8282/api/dsp",
  "contractId": "agreement-streaming-uuid",
  "transferType": "HttpData-PULL",
  "callbackAddresses": [
    {
      "transactional": false,
      "uri": "http://consumidor:8181/api/callbacks",
      "events": ["transfer.process.started", "transfer.process.terminated"]
    }
  ]
}
```

**Verificar estado (dia 1 — activa):**
```http
GET http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid/state
```
```json
{ "@type": "TransferState", "state": "STARTED" }
```

**Verificar estado (dia 8 — terminada por el monitor):**
```http
GET http://consumidor:8181/api/management/v3/transferprocesses/transfer-uuid/state
```
```json
{ "@type": "TransferState", "state": "TERMINATED" }
```

El consumidor tambien recibiria un callback a `http://consumidor:8181/api/callbacks` con el evento `transfer.process.terminated`.

---

## Otros Escenarios

### Escenario B: Policy con fecha fija de fin

```json
{
  "leftOperand": "edc:inForceDate",
  "operator": "lteq",
  "rightOperand": "2025-06-30T23:59:59Z"
}
```

Todas las transferencias bajo este contrato se terminan automaticamente el 1 de julio de 2025, sin importar cuando se firmaron.

### Escenario C: Transferencia finita (no necesita monitor)

Una transferencia PUSH de un fichero (envia y termina) pasa por el estado STARTED brevemente y luego llega a COMPLETED. El Policy Monitor la detecta como completada y deja de monitorearla:

```java
if (transferProcess.getState() >= TransferProcessStates.COMPLETING.code()) {
    entry.transitionToCompleted();  // Dejar de monitorear
    return true;
}
```

### Escenario D: Contrato eliminado durante transferencia activa

Si alguien borra el ContractAgreement mientras la transferencia esta activa:

```java
var contractAgreement = contractAgreementService.findById(entry.getContractId());
if (contractAgreement == null) {
    entry.transitionToFailed("ContractAgreement does not exist");
    // La transferencia NO se termina automaticamente en este caso
    // El entry queda en FAILED
}
```

---

## Configuracion del Policy Monitor

```properties
# Cuantas entries procesar por iteracion (default: depende de StateMachineConfiguration)
edc.policy.monitor.batchSize=20

# Tiempo de espera entre iteraciones (milisegundos, con backoff exponencial)
edc.policy.monitor.iterationWait=500

# Reintentos maximos para operaciones fallidas
edc.policy.monitor.sendRetryLimit=5

# Delay base para reintentos con backoff exponencial
edc.policy.monitor.sendRetryBaseDelay=100

# Datasource SQL (solo si se usa el store SQL en vez del in-memory)
edc.sql.store.policy-monitor.datasource=default
```

---

## Sistema de Leasing (Multi-instancia)

El Policy Monitor soporta **despliegue multi-instancia**. Para evitar que dos instancias procesen la misma entry:

1. `nextNotLeased(batchSize)` solo devuelve entries sin lease activo
2. Al tomar una entry, se le asigna un lease con duracion limitada
3. Si la instancia se cae, el lease expira y otra instancia toma el relevo
4. El `stateTimestamp` se actualiza en cada iteracion para garantizar fairness (FIFO)

---

## Resumen de los 4 Scopes de Policy

| # | Scope | Momento | Que evalua | Datos disponibles |
|---|-------|---------|-----------|-------------------|
| 1 | `catalog.request` | Peticion de catalogo | Quien puede **ver** assets | `ParticipantAgent` |
| 2 | `contract.negotiation` | Negociacion de contrato | Condiciones de **uso** | `ParticipantAgent` |
| 3 | `transfer.process` | Inicio de transferencia | Validez **actual** del acuerdo | `ParticipantAgent`, `ContractAgreement`, `Instant.now()` |
| 4 | `policy.monitor` | Continuo post-inicio | Validez **temporal continua** | `ContractAgreement`, `Instant.now()` |

---

## Archivos Clave en el Repositorio

| Archivo | Rol |
|---------|-----|
| `core/policy-monitor/policy-monitor-core/src/.../PolicyMonitorManagerImpl.java` | Maquina de estados del monitor |
| `core/policy-monitor/policy-monitor-core/src/.../PolicyMonitorExtension.java` | Inicializacion, registra scope y funciones |
| `core/policy-monitor/policy-monitor-core/src/.../StartMonitoring.java` | Subscriber que activa monitoreo en STARTED |
| `spi/policy-monitor/policy-monitor-spi/src/.../PolicyMonitorContext.java` | Contexto con `now()` y `contractAgreement()` |
| `spi/policy-monitor/policy-monitor-spi/src/.../PolicyMonitorEntry.java` | Entidad con estados STARTED/COMPLETED/FAILED |
| `core/control-plane/lib/control-plane-policies-lib/src/.../ContractExpiryCheckFunction.java` | Funcion que evalua `edc:inForceDate` |
| `extensions/policy-monitor/store/sql/policy-monitor-store-sql/` | Persistencia SQL (PostgreSQL) |
