# Caso de Uso 2: contract.negotiation — Evaluacion de Politicas en Negociacion de Contrato

## Resumen

Cuando un consumidor quiere acceder a un asset, no basta con verlo en el catalogo. Debe **negociar un contrato** con el proveedor. Durante esta negociacion, el proveedor evalua la **contract policy** contra las credenciales/claims del consumidor.

Si la access policy (caso 1) era el "guardia de puerta", la contract policy es el **"contrato legal"**: define las condiciones bajo las cuales se permite el uso de los datos.

---

## Diferencia entre Access Policy y Contract Policy

| Aspecto | Access Policy (caso 1) | Contract Policy (caso 2) |
|---------|------------------------|--------------------------|
| Cuando se evalua | Al pedir el catalogo | Al negociar el contrato |
| Visible al consumidor | NO | SI (viene en la oferta del catalogo) |
| Que controla | Quien puede **ver** el asset | Bajo que **condiciones** se puede usar |
| Ejemplo | "Solo partners gold" | "Uso maximo 30 dias, solo en la UE" |

---

## Maquina de Estados de la Negociacion

```
CONSUMIDOR                              PROVEEDOR
    |                                       |
    | INITIAL                               |
    |   → REQUESTING ---------------------->|
    |     (envia ContractRequestMessage)    |
    |                                       | REQUESTED
    |                                       |   → Evalua ACCESS policy ✓
    |                                       |   → Evalua CONTRACT policy ✓
    |                                       |   → AGREEING
    |                                       |     (genera ContractAgreement)
    |  AGREED <-----------------------------|   → AGREED
    |   (recibe ContractAgreementMessage)   |
    |   → VERIFYING ----------------------->|
    |     (envia verificacion)              | VERIFIED
    |                                       |   → FINALIZING
    |  FINALIZED <--------------------------|   → FINALIZED
    |                                       |
```

### Estados completos

| Estado | Codigo | Quien | Descripcion |
|--------|--------|-------|-------------|
| INITIAL | 50 | Ambos | Estado inicial |
| REQUESTING | 100 | Consumidor | Enviando peticion al proveedor |
| REQUESTED | 200 | Ambos | Peticion recibida |
| OFFERING | 300 | Proveedor | Enviando contra-oferta |
| OFFERED | 400 | Ambos | Oferta recibida |
| ACCEPTING | 700 | Consumidor | Aceptando oferta |
| ACCEPTED | 800 | Ambos | Oferta aceptada |
| AGREEING | 825 | Proveedor | Generando acuerdo |
| AGREED | 850 | Ambos | Acuerdo generado |
| VERIFYING | 1050 | Consumidor | Verificando acuerdo |
| VERIFIED | 1100 | Ambos | Acuerdo verificado |
| FINALIZING | 1150 | Proveedor | Finalizando |
| **FINALIZED** | **1200** | **Ambos** | **Estado final — exito** |
| TERMINATING | 1300 | Ambos | Terminando |
| **TERMINATED** | **1400** | **Ambos** | **Estado final — fallo/cancelacion** |

---

## Evaluacion de Politicas — Que Pasa Internamente

Cuando el proveedor recibe el `ContractRequestMessage` del consumidor, se ejecuta la validacion en `ContractValidationServiceImpl`:

```
validateInitialOffer(agent, consumerOffer)
│
├─ 1. Evaluar ACCESS POLICY (scope: catalog.request)
│     policyEngine.evaluate(accessPolicy, CatalogPolicyContext(agent))
│     → Si falla: RECHAZAR (el consumidor no deberia tener acceso)
│
├─ 2. Verificar que el ASSET existe
│     assetIndex.findById(assetId)
│     → Si no existe: RECHAZAR
│
├─ 3. Verificar que el asset esta en el ContractDefinition
│     assetIndex.countAssets(assetsSelector + assetId)
│     → Si no coincide: RECHAZAR
│
├─ 4. Evaluar CONTRACT POLICY (scope: contract.negotiation)
│     policyEngine.evaluate(contractPolicy, ContractNegotiationPolicyContext(agent))
│     → Si falla: RECHAZAR la negociacion
│     → Si pasa: continuar
│
└─ 5. Crear ContractOffer sanitizado
      → Se usa la policy del proveedor, NO la del consumidor
      → Esto evita que el consumidor inyecte politicas manipuladas
```

### Codigo fuente

Archivo: `core/control-plane/control-plane-contract/src/.../ContractValidationServiceImpl.java`

```java
private Result<Policy> validateInitialOffer(ValidatableConsumerOffer consumerOffer, ParticipantAgent agent) {
    // 1. Evaluar access policy
    var accessPolicyResult = policyEngine.evaluate(
        consumerOffer.getAccessPolicy(), new CatalogPolicyContext(agent));
    if (accessPolicyResult.failed()) {
        return accessPolicyResult.mapFailure();
    }

    // 2. Verificar que el asset existe
    var targetAsset = assetIndex.findById(consumerOffer.getOfferId().assetIdPart());
    if (targetAsset == null) {
        return failure("Invalid target: " + consumerOffer.getOfferId().assetIdPart());
    }

    // 3. Verificar que el asset esta en el ContractDefinition
    var testCriteria = new ArrayList<>(consumerOffer.getContractDefinition().getAssetsSelector());
    testCriteria.add(new Criterion(Asset.PROPERTY_ID, "=", consumerOffer.getOfferId().assetIdPart()));
    if (assetIndex.countAssets(testCriteria) <= 0) {
        return failure("Asset ID from the ContractOffer is not included in the ContractDefinition");
    }

    // 4. Evaluar contract policy
    var contractPolicy = consumerOffer.getContractPolicy()
        .withTarget(consumerOffer.getOfferId().assetIdPart());
    return policyEngine.evaluate(contractPolicy, new ContractNegotiationPolicyContext(agent))
            .map(v -> contractPolicy);
}
```

---

## Ejemplo Practico con Peticiones HTTP

### Escenario

Continuando del caso 1: el consumidor ya vio `dataset-publico` en el catalogo. Ahora quiere negociar un contrato para usarlo.

El proveedor ha configurado una **contract policy** que requiere que el consumidor acepte una clausula de uso no comercial.

### Configuracion Previa (Proveedor)

Esto ya se configuro en el caso 1, pero ahora la contract policy tiene condiciones reales:

```http
POST http://proveedor:8181/api/management/v3/policydefinitions
Content-Type: application/json
```

**Contract policy con restriccion de uso no comercial:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-uso-no-comercial",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": [
          {
            "leftOperand": "purpose",
            "operator": "eq",
            "rightOperand": "non-commercial"
          }
        ]
      }
    ]
  }
}
```

**Contract policy temporal (valida solo durante el contrato):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-contrato-temporal",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": [
          {
            "leftOperand": "inForceDate",
            "operator": "gteq",
            "rightOperand": "contractAgreement+0s"
          },
          {
            "leftOperand": "inForceDate",
            "operator": "lteq",
            "rightOperand": "contractAgreement+30d"
          }
        ]
      }
    ]
  }
}
```

**ContractDefinition usando estas politicas:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractDefinition",
  "@id": "contrato-datos-publicos",
  "accessPolicyId": "policy-acceso-abierto",
  "contractPolicyId": "policy-uso-no-comercial",
  "assetsSelector": [
    {
      "@type": "Criterion",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "dataset-publico"
    }
  ]
}
```

### Paso 1: El consumidor solicita el catalogo (repaso)

```http
POST http://consumidor:8181/api/management/v3/catalog/request
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "CatalogRequest",
  "counterPartyAddress": "http://proveedor:8282/api/dsp",
  "counterPartyId": "proveedor-id",
  "protocol": "dataspace-protocol-http"
}
```

**Respuesta** (simplificada): el catalogo incluye `dataset-publico` con una oferta que contiene la `policy-uso-no-comercial`. El consumidor ve las condiciones.

```json
{
  "@type": "dcat:Catalog",
  "dcat:dataset": [
    {
      "@id": "dataset-publico",
      "odrl:hasPolicy": [
        {
          "@id": "contrato-datos-publicos:dataset-publico:uuid-random",
          "@type": "odrl:Offer",
          "odrl:permission": [
            {
              "odrl:action": "use",
              "odrl:constraint": [
                {
                  "odrl:leftOperand": "purpose",
                  "odrl:operator": "eq",
                  "odrl:rightOperand": "non-commercial"
                }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

### Paso 2: El consumidor inicia la negociacion

El consumidor toma el `offerId` y la `policy` exactamente como vienen en el catalogo y las envia de vuelta en un `ContractRequest`:

```http
POST http://consumidor:8181/api/management/v3/contractnegotiations
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractRequest",
  "counterPartyAddress": "http://proveedor:8282/api/dsp",
  "protocol": "dataspace-protocol-http",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "odrl:Offer",
    "@id": "contrato-datos-publicos:dataset-publico:uuid-random",
    "assigner": "proveedor-id",
    "target": "dataset-publico",
    "permission": [
      {
        "action": "use",
        "constraint": [
          {
            "leftOperand": "purpose",
            "operator": "eq",
            "rightOperand": "non-commercial"
          }
        ]
      }
    ]
  },
  "callbackAddresses": [
    {
      "transactional": false,
      "uri": "http://consumidor:8181/api/callbacks",
      "events": ["contract.negotiation"]
    }
  ]
}
```

**Respuesta:**
```json
{
  "@type": "IdResponse",
  "@id": "negotiation-uuid-123",
  "createdAt": 1707900000000
}
```

### Paso 3: Consultar el estado de la negociacion

```http
GET http://consumidor:8181/api/management/v3/contractnegotiations/negotiation-uuid-123/state
```

**Respuesta durante el proceso:**
```json
{
  "@type": "NegotiationState",
  "state": "REQUESTING"
}
```

**Respuesta cuando se completa:**
```json
{
  "@type": "NegotiationState",
  "state": "FINALIZED"
}
```

**Respuesta si la policy falla:**
```json
{
  "@type": "NegotiationState",
  "state": "TERMINATED"
}
```

### Paso 4: Obtener el acuerdo (si fue exitoso)

```http
GET http://consumidor:8181/api/management/v3/contractnegotiations/negotiation-uuid-123/agreement
```

**Respuesta:**
```json
{
  "@type": "ContractAgreement",
  "@id": "agreement-uuid-456",
  "assetId": "dataset-publico",
  "consumerId": "consumidor-id",
  "providerId": "proveedor-id",
  "contractSigningDate": 1707900000,
  "policy": {
    "@type": "odrl:Agreement",
    "permission": [
      {
        "action": "use",
        "target": "dataset-publico",
        "constraint": [
          {
            "leftOperand": "purpose",
            "operator": "eq",
            "rightOperand": "non-commercial"
          }
        ]
      }
    ]
  }
}
```

### Paso 5 (opcional): Terminar una negociacion

```http
POST http://consumidor:8181/api/management/v3/contractnegotiations/negotiation-uuid-123/terminate
Content-Type: application/json
```

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "TerminateNegotiation",
  "@id": "negotiation-uuid-123",
  "reason": "Las condiciones no son aceptables para nuestra organizacion"
}
```

---

## Escenarios de Evaluacion

### Escenario A: Negociacion exitosa

```
Consumidor con claim "purpose=non-commercial"
    → Access policy: PASA (policy abierta)
    → Contract policy: PASA (purpose coincide)
    → Resultado: FINALIZED + ContractAgreement generado
```

### Escenario B: Negociacion rechazada por contract policy

```
Consumidor con claim "purpose=commercial"
    → Access policy: PASA (policy abierta, puede ver el asset)
    → Contract policy: FALLA (purpose no coincide con "non-commercial")
    → Resultado: TERMINATED
```

### Escenario C: Negociacion rechazada por access policy

```
Consumidor sin credenciales de partner intenta negociar "dataset-partners"
    → Access policy: FALLA (no tiene partnerLevel=gold)
    → Contract policy: NO SE EVALUA (se corta antes)
    → Resultado: TERMINATED
```

### Escenario D: Asset no existe o no coincide

```
Consumidor envia offerId con un asset que no esta en el ContractDefinition
    → Access policy: PASA
    → Asset check: FALLA ("Asset ID not included in ContractDefinition")
    → Contract policy: NO SE EVALUA
    → Resultado: TERMINATED
```

---

## Detalle Importante: Sanitizacion de la Policy

El proveedor **nunca usa la policy que envio el consumidor**. El flujo es:

1. El consumidor envia un `offerId` (que contiene el ID del ContractDefinition y del asset)
2. El proveedor extrae el `contractDefinitionId` del `offerId`
3. El proveedor carga **su propia** contract policy desde el `PolicyDefinitionStore`
4. Evalua **esa** policy (no la del consumidor) contra los claims del agente
5. Genera el `ContractAgreement` con **su** policy

Esto evita que un consumidor malicioso envie una policy modificada (por ejemplo, eliminando constraints).

```java
// Se usa la policy del proveedor, no la del consumidor
var contractPolicy = consumerOffer.getContractPolicy()  // ← cargada del store del proveedor
    .withTarget(consumerOffer.getOfferId().assetIdPart());
return policyEngine.evaluate(contractPolicy, new ContractNegotiationPolicyContext(agent));
```

---

## Scopes de Policy en la Negociacion

Se usan **3 scopes** diferentes durante la negociacion:

| Scope | Clase | Cuando |
|-------|-------|--------|
| `catalog.request` | `CatalogPolicyContext` | Verificar access policy (incluso al negociar) |
| `contract.negotiation` | `ContractNegotiationPolicyContext` | Evaluar la contract policy principal |
| `request.contract.negotiation` | `RequestContractNegotiationPolicyContext` | Validar tokens JWT en cada mensaje DSP entrante |

---

## Eventos de la Negociacion

Cada transicion de estado emite un evento observable:

| Evento | Cuando |
|--------|--------|
| `ContractNegotiationInitiated` | Consumidor crea la negociacion |
| `ContractNegotiationRequested` | Proveedor recibe la peticion |
| `ContractNegotiationOffered` | Proveedor envia contra-oferta |
| `ContractNegotiationAccepted` | Consumidor acepta |
| `ContractNegotiationAgreed` | Proveedor genera acuerdo |
| `ContractNegotiationVerified` | Consumidor verifica acuerdo |
| `ContractNegotiationFinalized` | Negociacion completada (incluye agreement) |
| `ContractNegotiationTerminated` | Negociacion fallida (incluye razon) |

Se pueden capturar via `callbackAddresses` en el `ContractRequest`.

---

## Archivos Clave en el Repositorio

| Archivo | Rol |
|---------|-----|
| `core/control-plane/control-plane-contract/src/.../ContractValidationServiceImpl.java` | Logica de validacion con PolicyEngine |
| `core/control-plane/control-plane-contract-manager/src/.../ConsumerContractNegotiationManagerImpl.java` | Maquina de estados del consumidor |
| `core/control-plane/control-plane-contract-manager/src/.../ProviderContractNegotiationManagerImpl.java` | Maquina de estados del proveedor |
| `core/control-plane/control-plane-aggregate-services/src/.../ContractNegotiationProtocolServiceImpl.java` | Manejo de mensajes DSP del protocolo |
| `spi/control-plane/contract-spi/src/.../ContractNegotiationPolicyContext.java` | Contexto de policy para negociacion |
| `spi/control-plane/contract-spi/src/.../ContractNegotiationStates.java` | Definicion de estados |
| `extensions/control-plane/api/management-api/contract-negotiation-api/` | Endpoints REST del Management API |
