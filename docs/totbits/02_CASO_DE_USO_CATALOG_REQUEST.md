# Caso de Uso 1: catalog.request — Filtrado de Assets por Access Policy

## Resumen

Cuando un consumidor solicita el catalogo a un proveedor, el proveedor **no devuelve todos sus assets**. Solo devuelve aquellos cuyas **access policies** permiten el acceso al participante que hace la peticion.

La access policy actua como un "guardia de puerta": si el participante no la cumple, ni siquiera sabe que ese asset existe.

---

## Conceptos Clave

| Concepto | Descripcion |
|----------|-------------|
| **Asset** | Recurso de datos que el proveedor ofrece (ej: un dataset, un API endpoint) |
| **PolicyDefinition** | Regla ODRL que define condiciones (permisos, prohibiciones, obligaciones) |
| **ContractDefinition** | Une un asset con dos politicas: una de acceso y una de contrato |
| **accessPolicyId** | Politica que controla **quien puede ver** el asset en el catalogo. NO se envia al consumidor |
| **contractPolicyId** | Politica que define **las condiciones de uso**. Se envia como parte de la oferta |
| **assetsSelector** | Criterios para seleccionar que assets aplican a este ContractDefinition |

---

## Flujo Completo

```
CONSUMIDOR                                    PROVEEDOR
    |                                              |
    |  POST /v4beta/catalog/request                |
    |  {counterPartyAddress, protocol}             |
    |--------------------------------------------->|
    |                                              |
    |                          1. Cargar todos los ContractDefinitions
    |                          2. Para CADA ContractDefinition:
    |                             a. Cargar accessPolicy por ID
    |                             b. Evaluar accessPolicy con PolicyEngine
    |                                contra el ParticipantAgent (claims del consumidor)
    |                             c. Si FALLA → descartar este ContractDefinition
    |                             d. Si PASA → mantener
    |                          3. Para cada asset del proveedor:
    |                             a. Aplicar assetsSelector de los ContractDefinitions que pasaron
    |                             b. Si algun ContractDefinition aplica al asset:
    |                                - Cargar contractPolicy
    |                                - Crear oferta con esa contractPolicy
    |                             c. Si ningun ContractDefinition aplica → asset no aparece
    |                          4. Construir catalogo con los datasets resultantes
    |                                              |
    |  <-- Catalogo (solo assets visibles)         |
    |<---------------------------------------------|
```

---

## Codigo Fuente Relevante

### ContractDefinitionResolverImpl

Archivo: `core/control-plane/control-plane-catalog/src/main/java/.../ContractDefinitionResolverImpl.java`

```java
// Para cada ContractDefinition, evalua la accessPolicy contra el agente
var definitions = definitionStore.findAll(query)
    .filter(definition -> {
        var accessResult = Optional.of(definition.getAccessPolicyId())
            .map(policyId -> policies.computeIfAbsent(policyId,
                key -> Optional.ofNullable(policyStore.findById(key))
                    .map(PolicyDefinition::getPolicy)
                    .orElse(null))
            )
            .map(policy -> policyEngine.evaluate(policy, new CatalogPolicyContext(agent)))
            .orElse(Result.failure("Policy not found"));

        return accessResult.succeeded(); // Solo incluye si la policy pasa
    })
    .toList();
```

### DatasetResolverImpl

Archivo: `core/control-plane/control-plane-catalog/src/main/java/.../DatasetResolverImpl.java`

```java
// Para cada asset, aplica los assetsSelector y genera ofertas con contractPolicy
contractDefinitions.stream()
    .filter(definition -> definition.getAssetsSelector().stream()
        .map(criterionOperatorRegistry::toPredicate)
        .reduce(x -> true, Predicate::and)
        .test(asset)  // El asset debe cumplir los criterios del selector
    )
    .forEach(contractDefinition -> {
        var policy = policies.get(contractDefinition.getContractPolicyId());
        if (policy != null) {
            var contractId = ContractOfferId.create(contractDefinition.getId(), asset.getId());
            datasetBuilder.offer(contractId.toString(), policy);
        }
    });
```

---

## Ejemplo Practico con Peticiones HTTP

### Escenario

Un proveedor tiene 3 assets:
- `dataset-publico` — visible para todos
- `dataset-partners` — solo visible para partners con credencial "partnerLevel=gold"
- `dataset-interno` — solo visible internamente (nadie externo lo ve)

### Configuracion del Proveedor

Todas las peticiones van al Management API del proveedor: `http://proveedor:8181/api/management`

#### Paso 1: Crear los assets

```http
POST /api/management/v3/assets
Content-Type: application/json
```

**Asset publico:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "Asset",
  "@id": "dataset-publico",
  "properties": {
    "name": "Dataset Publico de Transporte",
    "description": "Datos abiertos de transporte urbano",
    "contenttype": "application/json"
  },
  "dataAddress": {
    "@type": "DataAddress",
    "type": "HttpData",
    "baseUrl": "https://datos.proveedor.com/transporte"
  }
}
```

**Asset para partners:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "Asset",
  "@id": "dataset-partners",
  "properties": {
    "name": "Dataset Premium de Energia",
    "description": "Datos de consumo energetico - solo partners",
    "contenttype": "application/json"
  },
  "dataAddress": {
    "@type": "DataAddress",
    "type": "HttpData",
    "baseUrl": "https://datos.proveedor.com/energia"
  }
}
```

**Asset interno:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "Asset",
  "@id": "dataset-interno",
  "properties": {
    "name": "Dataset Interno de RRHH",
    "description": "Datos internos de recursos humanos",
    "contenttype": "application/json"
  },
  "dataAddress": {
    "@type": "DataAddress",
    "type": "HttpData",
    "baseUrl": "https://datos.proveedor.com/rrhh"
  }
}
```

#### Paso 2: Crear las policy definitions

```http
POST /api/management/v3/policydefinitions
Content-Type: application/json
```

**Politica abierta (permite a todos):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-acceso-abierto",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [
      {
        "action": "use"
      }
    ]
  }
}
```

**Politica solo partners gold (requiere credencial):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-acceso-partners-gold",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": [
          {
            "leftOperand": "partnerLevel",
            "operator": "eq",
            "rightOperand": "gold"
          }
        ]
      }
    ]
  }
}
```

**Politica que deniega todo (nadie accede):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-acceso-denegado",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "prohibition": [
      {
        "action": "use"
      }
    ]
  }
}
```

**Politica de contrato (condiciones de uso, comun para todos):**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "PolicyDefinition",
  "@id": "policy-contrato-estandar",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [
      {
        "action": "use"
      }
    ]
  }
}
```

#### Paso 3: Crear las contract definitions (unen assets con politicas)

```http
POST /api/management/v3/contractdefinitions
Content-Type: application/json
```

**ContractDefinition para dataset publico:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractDefinition",
  "@id": "contrato-publico",
  "accessPolicyId": "policy-acceso-abierto",
  "contractPolicyId": "policy-contrato-estandar",
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

**ContractDefinition para dataset de partners:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractDefinition",
  "@id": "contrato-partners",
  "accessPolicyId": "policy-acceso-partners-gold",
  "contractPolicyId": "policy-contrato-estandar",
  "assetsSelector": [
    {
      "@type": "Criterion",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "dataset-partners"
    }
  ]
}
```

**ContractDefinition para dataset interno:**
```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractDefinition",
  "@id": "contrato-interno",
  "accessPolicyId": "policy-acceso-denegado",
  "contractPolicyId": "policy-contrato-estandar",
  "assetsSelector": [
    {
      "@type": "Criterion",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "dataset-interno"
    }
  ]
}
```

#### Paso 4: El consumidor pide el catalogo

Desde el Management API del **consumidor**: `http://consumidor:8181/api/management`

```http
POST /api/management/v3/catalog/request
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

### Resultados segun quien pide el catalogo

#### Consumidor sin credenciales especiales

Ve **1 dataset**:
- `dataset-publico` — la access policy abierta pasa para todos

No ve:
- `dataset-partners` — no tiene claim `partnerLevel=gold`
- `dataset-interno` — la access policy prohíbe todo

#### Consumidor con credencial `partnerLevel=gold`

Ve **2 datasets**:
- `dataset-publico` — access policy abierta
- `dataset-partners` — tiene el claim requerido

No ve:
- `dataset-interno` — la access policy prohíbe todo

#### Ningun consumidor externo

Ve `dataset-interno`: **nunca**. La prohibition lo bloquea siempre.

---

## Lo que el consumidor recibe vs lo que NO recibe

| Elemento | Visible para el consumidor? |
|----------|-----------------------------|
| Asset (propiedades, id) | Si, si pasa la access policy |
| Access policy | **NO** — nunca se expone al consumidor |
| Contract policy | **SI** — se incluye como parte de la oferta |
| DataAddress (URL real) | **NO** — solo se expone tras completar la transferencia |
| ContractDefinition ID | Parcial — esta codificado dentro del ContractOfferId |

---

## Nota sobre las constraints de las policies

Para que una constraint como `"leftOperand": "partnerLevel"` funcione, se necesita:

1. **Registrar una `AtomicConstraintRuleFunction`** en el `PolicyEngine` que sepa evaluar `partnerLevel`
2. Esta funcion recibe el `ParticipantAgent` (que contiene los claims del consumidor) y compara contra el `rightOperand`
3. Los claims vienen del `ClaimToken` que el `IdentityService` construyo al verificar las credenciales del consumidor

Sin registrar la funcion correspondiente, el PolicyEngine **no sabe evaluar** la constraint y la politica fallara por defecto.

---

## Configuracion del Management API

```properties
# Proveedor
web.http.management.port=8181
web.http.management.path=/api/management

# Puerto del protocolo DSP (donde el consumidor contacta al proveedor)
web.http.protocol.port=8282
web.http.protocol.path=/api/dsp
```

---

## Archivos clave en el repositorio

| Archivo | Rol |
|---------|-----|
| `core/control-plane/control-plane-catalog/src/.../ContractDefinitionResolverImpl.java` | Filtra ContractDefinitions por access policy |
| `core/control-plane/control-plane-catalog/src/.../DatasetResolverImpl.java` | Construye datasets con ofertas usando contract policy |
| `extensions/control-plane/api/management-api/catalog-api/` | Endpoint REST para solicitar catalogo |
| `extensions/control-plane/api/management-api/asset-api/` | Endpoint REST para gestionar assets |
| `extensions/control-plane/api/management-api/policy-definition-api/` | Endpoint REST para gestionar policies |
| `extensions/control-plane/api/management-api/contract-definition-api/` | Endpoint REST para gestionar contract definitions |
| `spi/common/policy-engine-spi/` | Interfaces del motor de politicas |
