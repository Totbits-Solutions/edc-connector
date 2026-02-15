# Casos de Uso — Evaluacion de Politicas en EDC Connector

## Objetivo

Esta documentacion demuestra **como y cuando el EDC Connector evalua politicas** a lo largo del ciclo de vida completo de un intercambio de datos soberano: desde que un consumidor descubre un asset hasta que la transferencia de datos termina.

El EDC Connector utiliza un motor de politicas propio basado en **ODRL (Open Digital Rights Language)**, no Open Policy Agent. Las politicas se evaluan en 4 momentos distintos, cada uno con un scope, un contexto y un proposito diferente.

---

## Contexto General

En un dataspace, dos participantes intercambian datos bajo condiciones acordadas:

```
CONSUMIDOR                                          PROVEEDOR
    |                                                   |
    |  1. "Que assets tienes?"           catalog.request |
    |  2. "Quiero usar este asset"  contract.negotiation |
    |  3. "Enviame los datos"          transfer.process  |
    |  4. (vigilancia continua)         policy.monitor   |
```

El proveedor protege sus datos con **dos tipos de politicas**:
- **Access policy** — controla quien puede ver un asset (nunca se expone al consumidor)
- **Contract policy** — define las condiciones de uso (se incluye en la oferta y en el acuerdo)

Ambas se configuran en el **ContractDefinition**, que es el objeto que une assets con politicas.

---

## Mapa de Documentos

| # | Documento | Que explica |
|---|-----------|-------------|
| 00 | [00_INDICE_CASOS_DE_USO.md](00_INDICE_CASOS_DE_USO.md) | Este indice — vision general y relacion entre los casos |
| 01 | [01_POLITICAS_IDENTIDAD_ARQUITECTURA.md](01_POLITICAS_IDENTIDAD_ARQUITECTURA.md) | Arquitectura general: capas del sistema, Identity Hub, motor de politicas, scopes |
| 02 | [02_CASO_DE_USO_CATALOG_REQUEST.md](02_CASO_DE_USO_CATALOG_REQUEST.md) | Caso 1: filtrado de assets por access policy al pedir el catalogo |
| 03 | [03_CASO_DE_USO_CONTRACT_NEGOTIATION.md](03_CASO_DE_USO_CONTRACT_NEGOTIATION.md) | Caso 2: evaluacion de contract policy durante negociacion |
| 04 | [04_CASO_DE_USO_TRANSFER_PROCESS.md](04_CASO_DE_USO_TRANSFER_PROCESS.md) | Caso 3: re-evaluacion de la policy del agreement al iniciar transferencia |
| 05 | [05_CASO_DE_USO_POLICY_MONITOR.md](05_CASO_DE_USO_POLICY_MONITOR.md) | Caso 4: vigilancia continua de politicas en transferencias activas |
| — | [EDC_Policy_Evaluation_UseCases.postman_collection.json](EDC_Policy_Evaluation_UseCases.postman_collection.json) | Coleccion Postman con todos los endpoints para ejecutar los 4 casos |

---

## Caso 1: catalog.request — "Puedo ver este asset?"

### Que se pretende demostrar

Que el proveedor **filtra los assets visibles** segun la access policy de cada ContractDefinition. Un consumidor sin las credenciales adecuadas nunca llega a saber que ciertos assets existen.

### Escenario

Un proveedor tiene 3 assets con 3 niveles de acceso:

| Asset | Access Policy | Resultado |
|-------|---------------|-----------|
| `dataset-publico` | Abierta | Visible para todos |
| `dataset-partners` | Requiere `partnerLevel=gold` | Solo visible para partners gold |
| `dataset-interno` | Prohibicion total | Invisible para cualquier externo |

### Que se evalua

- **Scope**: `catalog.request`
- **Contexto**: `CatalogPolicyContext(ParticipantAgent)`
- **Datos disponibles**: Claims/credenciales del consumidor
- **Codigo clave**: `ContractDefinitionResolverImpl.resolveFor()` — filtra ContractDefinitions cuya access policy pasa para el agente

### Leccion clave

La access policy es el primer filtro de seguridad. Si no pasa, el asset ni aparece en el catalogo. El consumidor nunca ve la access policy — solo ve las contract policies (condiciones de uso) de los assets a los que tiene acceso.

---

## Caso 2: contract.negotiation — "Puedo acordar condiciones para usarlo?"

### Que se pretende demostrar

Que el proveedor **re-evalua tanto la access policy como la contract policy** cuando recibe una solicitud de negociacion. Ademas, el proveedor **nunca usa la policy enviada por el consumidor** — carga su propia policy del store para evitar manipulacion.

### Escenario

El consumidor vio `dataset-publico` en el catalogo y quiere negociar un contrato. El proveedor ejecuta 4 validaciones secuenciales:

1. Access policy del consumidor → PASA
2. El asset existe → PASA
3. El asset esta en el ContractDefinition → PASA
4. Contract policy (condiciones de uso) → evalua claims del consumidor

### Que se evalua

- **Scope**: `contract.negotiation`
- **Contexto**: `ContractNegotiationPolicyContext(ParticipantAgent)`
- **Datos disponibles**: Claims/credenciales del consumidor
- **Codigo clave**: `ContractValidationServiceImpl.validateInitialOffer()` — ejecuta las 4 validaciones en secuencia

### Escenarios demostrados

| Escenario | Access Policy | Contract Policy | Resultado |
|-----------|---------------|-----------------|-----------|
| A: Claims correctos | PASA | PASA | `FINALIZED` + ContractAgreement |
| B: Purpose incorrecto | PASA | FALLA | `TERMINATED` |
| C: Sin credenciales de partner | FALLA | No se evalua | `TERMINATED` |
| D: Asset no en ContractDefinition | PASA | No se evalua | `TERMINATED` |

### Leccion clave

La sanitizacion es critica: el proveedor descarta la policy del consumidor y usa la suya propia. Esto previene ataques donde un consumidor malicioso envia una policy modificada (por ejemplo, eliminando constraints).

---

## Caso 3: transfer.process — "Puedo obtener los datos ahora?"

### Que se pretende demostrar

Que tener un ContractAgreement **no garantiza acceso perpetuo** a los datos. Al iniciar una transferencia, el proveedor **re-evalua la policy del acuerdo con el momento actual** (`Instant.now()`). Si las condiciones han cambiado desde la firma (credenciales revocadas, restriccion temporal expirada), la transferencia se rechaza.

### Escenario

El consumidor tiene un agreement firmado hace tiempo e intenta iniciar una transferencia. El proveedor verifica:

1. Token JWT del consumidor (autenticidad del mensaje)
2. Identidad del consumidor == consumerId del agreement
3. Policy del agreement evaluada **con la fecha actual**
4. TransferType soportado y dataDestination valida

### Que se evalua

- **Scope**: `transfer.process`
- **Contexto**: `TransferProcessPolicyContext(ParticipantAgent, ContractAgreement, Instant.now())`
- **Datos disponibles**: Claims del consumidor + acuerdo firmado + momento actual
- **Codigo clave**: `ContractValidationServiceImpl.validateAgreement()` — crea contexto temporal y evalua

### Escenarios demostrados

| Escenario | Que falla | Resultado |
|-----------|-----------|-----------|
| A: Todo valido | Nada | `STARTED` |
| B: Policy temporal expirada | `Instant.now()` fuera de ventana | `TERMINATED` |
| C: Credenciales revocadas | JWT invalido | `TERMINATED` |
| D: Identidad incorrecta | `agent.identity != agreement.consumerId` | `TERMINATED` |
| E: TransferType no soportado | Data plane no soporta el tipo | `TERMINATED` |

### Leccion clave

El `Instant.now()` en el contexto es lo que permite detectar cambios temporales. La misma policy que paso durante la negociacion puede fallar meses despues si incluia restricciones como `inForceDate lteq contractAgreement+30d`.

---

## Caso 4: policy.monitor — "Sigo pudiendo usar los datos?"

### Que se pretende demostrar

Que para **transferencias de larga duracion** (streaming, APIs con acceso continuo), el EDC mantiene un proceso en segundo plano que **re-evalua periodicamente** la policy del contrato. Cuando la policy deja de cumplirse, la transferencia se termina automaticamente sin intervencion humana.

### Escenario

Un consumidor accede a un stream de datos IoT con un contrato que permite uso durante 7 dias. El Policy Monitor:

- Dia 1-7: evalua la policy → PASA → transferencia sigue activa
- Dia 8: evalua la policy → FALLA (fuera de ventana temporal) → termina la transferencia automaticamente

### Que se evalua

- **Scope**: `policy.monitor`
- **Contexto**: `PolicyMonitorContext(Instant.now(), ContractAgreement)`
- **Datos disponibles**: Momento actual + acuerdo (SIN ParticipantAgent — solo evalua restricciones temporales)
- **Codigo clave**: `PolicyMonitorManagerImpl.processMonitoring()` — ciclo continuo de evaluacion
- **Funcion registrada**: `ContractExpiryCheckFunction` — evalua `edc:inForceDate` con fechas fijas o relativas a la firma

### Comportamiento demostrado

| Situacion | Accion del Monitor |
|-----------|-------------------|
| Policy pasa | Actualiza timestamp, sigue monitoreando |
| Policy falla | Ejecuta `TerminateTransferCommand`, deja de monitorear |
| Transfer ya completada | Deja de monitorear |
| Transfer no encontrada | Marca como FAILED |
| Contrato no encontrado | Marca como FAILED |

### Leccion clave

El Policy Monitor es **automatico y solo actua en el lado del proveedor**. Se activa via el evento `TransferProcessStarted` (solo tipo PROVIDER). Soporta despliegue multi-instancia con sistema de leasing para evitar procesamiento concurrente.

---

## Tabla Comparativa de los 4 Scopes

| Aspecto | catalog.request | contract.negotiation | transfer.process | policy.monitor |
|---------|-----------------|---------------------|-----------------|----------------|
| **Cuando** | Al pedir catalogo | Al negociar contrato | Al iniciar transferencia | Continuamente post-inicio |
| **Tipo** | Puntual | Puntual | Puntual | Ciclico |
| **Quien evalua** | Proveedor | Proveedor | Proveedor | Proveedor (automatico) |
| **ParticipantAgent** | Si | Si | Si | No |
| **ContractAgreement** | No | No | Si | Si |
| **Instant.now()** | No | No | Si | Si |
| **Que policy evalua** | Access policy | Contract policy | Policy del agreement | Policy del agreement |
| **Si falla** | Asset invisible | Negociacion rechazada | Transferencia rechazada | Transferencia terminada |

---

## Coleccion Postman

El fichero `EDC_Policy_Evaluation_UseCases.postman_collection.json` contiene **34 requests** organizados en 5 carpetas que permiten ejecutar los 4 casos de uso de forma secuencial.

### Como usar

1. **Importar** en Postman: Import > File > seleccionar el JSON
2. **Configurar variables** de la coleccion:
   - `PROVIDER_MANAGEMENT_URL` — Management API del proveedor (default: `http://localhost:18181/api/management`)
   - `CONSUMER_MANAGEMENT_URL` — Management API del consumidor (default: `http://localhost:28181/api/management`)
   - `PROVIDER_DSP_URL` — Endpoint DSP del proveedor
   - `PROVIDER_ID` — Participant ID del proveedor
   - `PROTOCOL` — Protocolo DSP a usar
3. **Ejecutar en orden**: Setup → Caso 1 → Caso 2 → Caso 3 → Caso 4
4. Los IDs se propagan automaticamente entre requests via scripts de test

### Estructura

| Carpeta | Requests | Proposito |
|---------|----------|-----------|
| 0. Setup Proveedor | 11 | Crear assets, policies y contract definitions |
| 1. catalog.request | 3 | Solicitar catalogo completo, filtrado y dataset especifico |
| 2. contract.negotiation | 6 | Negociar, consultar estado, obtener agreement, terminar |
| 3. transfer.process | 8 | PUSH/PULL, estado, suspender, reanudar, terminar |
| 4. policy.monitor | 8 | Flujo streaming completo + policy de 60s para prueba rapida |
| 5. Consultas Auxiliares | 8 | Listar y eliminar recursos del proveedor |

---

## Prerequisitos para Ejecutar

Para ejecutar los casos de uso se necesitan **dos instancias de EDC Connector** corriendo (un proveedor y un consumidor) con:

- Management API habilitado (`extensions/common/api/management-api-configuration/`)
- Protocolo DSP habilitado (`data-protocols/dsp/`)
- Motor de politicas con funciones registradas (`core/common/lib/policy-engine-lib/`)
- Policy Monitor habilitado (`core/policy-monitor/policy-monitor-core/`) para el caso 4
- Un mecanismo de identidad (puede ser `iam-mock` para pruebas)
